package com.igviewer.archive.data

import org.json.JSONException
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * In-memory orchestration of the Rust core's `index_archive`: scan the export,
 * parse each thread's files in ascending number order, apply canonical ingest, and
 * expose summaries + per-thread messages.
 *
 * This is the Kotlin reimplementation chosen over the UniFFI bridge. It mirrors the
 * documented dedupe/ordering/containment rules but does not persist yet.
 */
class ArchiveIndex private constructor(
    val root: File,
    val threads: List<ThreadSummary>,
    private val messagesByKey: Map<String, List<KeptMessage>>,
    val skippedFiles: Int,
    val parseFailures: Int,
    val skipReasons: Map<SkipReason, Int>,
) {
    fun messages(key: String): List<KeptMessage> = messagesByKey[key].orEmpty()

    fun thread(key: String): ThreadSummary? = threads.firstOrNull { it.key == key }

    /**
     * Resolve a media URI to a file inside the export, tolerant of the two known
     * quirks: the export root may already be `your_instagram_activity/` (so the URI's
     * leading segment is redundant), and hand-edited exports sometimes move a file
     * so the path no longer matches (fall back to a basename match within the root).
     * Never returns a path outside [root].
     */
    fun mediaFile(uri: String): File? {
        try {
            return MediaResolver.resolve(root, uri)
        } catch (_: Exception) {
        }
        if (uri.startsWith("your_instagram_activity/")) {
            try {
                return MediaResolver.resolve(root, uri.removePrefix("your_instagram_activity/"))
            } catch (_: Exception) {
            }
        }
        val name = uri.substringAfterLast('/')
        if (name.isEmpty()) return null
        return mediaIndex[name]
    }

    private val mediaIndex: Map<String, File> by lazy {
        val map = HashMap<String, File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (d, depth) = stack.removeLast()
            if (depth > 8) continue
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (isSymlink(k)) continue
                if (k.isDirectory) stack.addLast(k to depth + 1)
                else map.putIfAbsent(k.name, k)
            }
        }
        map
    }

    /** One participant is "me"; the app defaults to the literal "me" when present. */
    fun detectMe(key: String): String {
        val msgs = messages(key)
        if (msgs.any { it.sender == "me" }) return "me"
        return thread(key)?.participants?.firstOrNull() ?: ""
    }

    companion object {
        fun index(root: File): ArchiveIndex {
            val scan = Scanner.scan(root)
            val summaries = ArrayList<ThreadSummary>()
            val byKey = HashMap<String, List<KeptMessage>>()
            var parseFailures = 0

            for (t in scan.threads) {
                // Files are already ascending by number from Scanner.
                val files = ArrayList<Pair<Long, List<Ingest.RawMessage>>>()
                var title = ""
                var participants = emptyList<String>()
                for (p in t.files) {
                    val no = when (val n = Scanner.parseFileNumber(p.name)) {
                        is Scanner.FileNo.Ok -> n.n
                        else -> { parseFailures++; continue }
                    }
                    val bytes = Scanner.readBounded(p)
                    if (bytes.isFailure) {
                        parseFailures++; continue
                    }
                    val text = try {
                        decodeText(bytes.getOrThrow())
                    } catch (_: Exception) {
                        parseFailures++; continue
                    }
                    val parsed = try {
                        Ingest.parseThreadFile(text)
                    } catch (_: JSONException) {
                        parseFailures++; continue
                    }
                    if (title.isEmpty() && participants.isEmpty()) {
                        title = parsed.title
                        participants = parsed.participants
                    }
                    files.add(no to parsed.messages)
                }

                if (files.isEmpty()) continue

                val ingest = Ingest.ingestThread(title, participants, files)
                byKey[t.key] = ingest.kept
                summaries.add(
                    ThreadSummary(
                        key = t.key,
                        title = ingest.title,
                        participants = ingest.participants,
                        keptCount = ingest.kept.size,
                        rawCount = ingest.rawCount,
                        firstTsMs = ingest.kept.firstOrNull()?.tsMs ?: 0L,
                        lastTsMs = ingest.kept.lastOrNull()?.tsMs ?: 0L,
                    )
                )
            }

            // Match the desktop list order: most recent thread first.
            summaries.sortWith(
                compareByDescending<ThreadSummary> { it.lastTsMs }.thenByDescending { it.title }
            )
            return ArchiveIndex(
                root = root,
                threads = summaries,
                messagesByKey = byKey,
                skippedFiles = scan.totalSkipped,
                parseFailures = parseFailures,
                skipReasons = scan.skipped,
            )
        }

        private fun isSymlink(f: File): Boolean =
            try {
                java.nio.file.Files.isSymbolicLink(f.toPath())
            } catch (_: Exception) {
                false
            }

        private fun strictUtf8(bytes: ByteArray): String {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }

        /**
         * Decode a JSON file to text. Instagram writes UTF-8; strip any BOM, and
         * if the bytes are not strict UTF-8 fall back to ISO-8859-1 rather than
         * dropping the whole thread -- Latin-1 never fails and reproduces exactly
         * the mojibake [Text.fix] is designed to repair.
         */
        private fun decodeText(bytes: ByteArray): String {
            val off = if (
                bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) 3 else 0
            val body = if (off == 0) bytes else bytes.copyOfRange(off, bytes.size)
            return try {
                strictUtf8(body)
            } catch (_: Exception) {
                String(body, StandardCharsets.ISO_8859_1)
            }
        }
    }
}
