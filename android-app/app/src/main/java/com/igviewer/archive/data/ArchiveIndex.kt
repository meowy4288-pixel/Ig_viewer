package com.igviewer.archive.data

import org.json.JSONException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
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
    private val messagesByKey: MutableMap<String, List<KeptMessage>>,
    val skippedFiles: Int,
    val parseFailures: Int,
    val skipReasons: Map<SkipReason, Int>,
    private val threadDir: File? = null,
) {
    @Synchronized
    fun messages(key: String): List<KeptMessage> {
        messagesByKey[key]?.let { return it }
        val dir = threadDir ?: return emptyList()
        val f = File(dir, keyFileName(key))
        val loaded = (loadThread(f) ?: emptyList())
        messagesByKey[key] = loaded
        return loaded
    }

    private fun keyFileName(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun loadThread(file: File): List<KeptMessage>? {
        return try {
            ObjectInputStream(BufferedInputStream(FileInputStream(file))).use {
                it.readObject() as List<KeptMessage>
            }
        } catch (_: Exception) {
            null
        }
    }

    fun thread(key: String): ThreadSummary? = threads.firstOrNull { it.key == key }

    /**
     * Persist the indexed data so the next launch can reopen it without reparsing the
     * export. Only the derived result is stored (threads + kept messages + counts);
     * media is still resolved from [root] on demand, so the export stays read-only.
     * The file lives in the app's private storage and is removed by "Delete index".
     */
    fun saveSnapshot(file: File) {
        // Write manifest (threads + counts) — tiny, loads instantly.
        file.parentFile?.mkdirs()
        ObjectOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeObject(
                Persisted(
                    rootPath = root.absolutePath,
                    threads = ArrayList(threads),
                    skippedFiles = skippedFiles,
                    parseFailures = parseFailures,
                    skipReasons = HashMap(skipReasons),
                )
            )
        }
        // Write per-thread message files for lazy loading.
        val threadDir = File(file.parentFile, "threads")
        threadDir.deleteRecursively()
        threadDir.mkdirs()
        for ((key, msgs) in messagesByKey) {
            if (msgs.isNotEmpty()) {
                val f = File(threadDir, keyFileName(key))
                ObjectOutputStream(BufferedOutputStream(FileOutputStream(f))).use { out ->
                    out.writeObject(msgs)
                }
            }
        }
    }

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

    /**
     * Every distinct participant name across all threads, ranked by how many
     * threads they appear in (descending), then alphabetically. Your own name
     * usually tops this list because it is present in every thread.
     */
    fun allParticipantNames(): List<String> {
        val freq = LinkedHashMap<String, Int>()
        for (t in threads) {
            for (p in t.participants) {
                if (p.isBlank()) continue
                freq[p] = (freq[p] ?: 0) + 1
            }
        }
        return freq.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }

    companion object {
        private val IMAGE_KINDS = setOf("photos", "gifs", "sticker")
        private val VIDEO_KINDS = setOf("videos")
        private val VOICE_KINDS = setOf("audio_files")

        /**
         * Reopen a previously saved index, or null when there is none or it cannot be
         * read (e.g. written by an incompatible older build). Callers fall back to a
         * fresh [index] then.
         */
        fun loadSnapshot(file: File): ArchiveIndex? {
            if (!file.exists()) return null
            return try {
                val p = ObjectInputStream(BufferedInputStream(FileInputStream(file))).use {
                    it.readObject() as Persisted
                }
                val threadDir = file.parentFile?.let { File(it, "threads") }
                ArchiveIndex(
                    root = File(p.rootPath),
                    threads = p.threads,
                    messagesByKey = mutableMapOf(),
                    skippedFiles = p.skippedFiles,
                    parseFailures = p.parseFailures,
                    skipReasons = p.skipReasons,
                    threadDir = threadDir,
                )
            } catch (_: Exception) {
                null
            }
        }

        fun index(root: File, onProgress: ((done: Int, total: Int) -> Unit)? = null): ArchiveIndex {
            val scan = Scanner.scan(root)
            val summaries = ArrayList<ThreadSummary>()
            val byKey = HashMap<String, List<KeptMessage>>()
            var parseFailures = 0
            val totalThreads = scan.threads.size

            for ((threadIndex, t) in scan.threads.withIndex()) {
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

                if (files.isEmpty()) {
                    onProgress?.invoke(threadIndex + 1, totalThreads)
                    continue
                }

                val ingest = Ingest.ingestThread(title, participants, files)
                byKey[t.key] = ingest.kept
                val lastPreview = previewForChatList(ingest.kept)
                summaries.add(
                    ThreadSummary(
                        key = t.key,
                        title = ingest.title,
                        participants = ingest.participants,
                        keptCount = ingest.kept.size,
                        rawCount = ingest.rawCount,
                        firstTsMs = ingest.kept.firstOrNull()?.tsMs ?: 0L,
                        lastTsMs = ingest.kept.lastOrNull()?.tsMs ?: 0L,
                        lastPreview = lastPreview,
                    )
                )
                onProgress?.invoke(threadIndex + 1, totalThreads)
            }

            // Match the desktop list order: most recent thread first.
            summaries.sortWith(
                compareByDescending<ThreadSummary> { it.lastTsMs }.thenByDescending { it.title }
            )
            return ArchiveIndex(
                root = root,
                threads = summaries,
                messagesByKey = byKey.toMutableMap(),
                skippedFiles = scan.totalSkipped,
                parseFailures = parseFailures,
                skipReasons = scan.skipped,
                threadDir = null,
            )
        }

        private fun previewForChatList(msgs: List<KeptMessage>): String {
        val m = msgs.lastOrNull() ?: return ""
        if (m.isUnsent) return "Message unsent"
        val c = m.content?.takeIf { it.isNotBlank() }
        if (c != null) return c
        return when {
            m.media.any { it.kind in IMAGE_KINDS } -> "Photo"
            m.media.any { it.kind in VIDEO_KINDS } -> "Video"
            m.media.any { it.kind in VOICE_KINDS } -> "Voice note"
            m.share != null -> "Shared post"
            m.media.isNotEmpty() -> "Attachment"
            else -> ""
        }
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

    /** On-disk form of an index. Written and read only by this class. */
    private class Persisted(
        val rootPath: String,
        val threads: List<ThreadSummary>,
        val skippedFiles: Int,
        val parseFailures: Int,
        val skipReasons: Map<SkipReason, Int>,
    ) : java.io.Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}
