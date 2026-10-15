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
    val roots: List<File>,
    val threads: List<ThreadSummary>,
    private val messagesByKey: MutableMap<String, List<KeptMessage>>,
    val skippedFiles: Int,
    val parseFailures: Int,
    val skipReasons: Map<SkipReason, Int>,
    private val threadDir: File? = null,
    /**
     * Which root each thread was read from. Media inside a thread resolves against its
     * own archive first, so two exports that both contain a thread with the same key
     * (and the same relative media paths) cannot steal each other's photos.
     */
    private val rootByKey: Map<String, File> = emptyMap(),
    /**
     * Extra folders that hold media referenced by the chats but live outside every import
     * root. They are used for media lookup ONLY -- never scanned for threads -- so adding
     * one cannot silently bring in chats or replace the archive.
     */
    private val mediaRoots: List<File> = emptyList(),
) {
    /** The first root, kept for the single-archive paths that never had a list. */
    val root: File get() = roots.first()
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
                    rootPaths = roots.map { it.absolutePath },
                    rootByKeyPaths = rootByKey.mapValues { it.value.absolutePath },
                    mediaRootPaths = mediaRoots.map { it.absolutePath },
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
                // A small head copy so opening a chat can paint the first messages without
                // deserialising the whole thread. Same data, a fraction of the read.
                val head = msgs.take(HEAD_MESSAGES)
                if (head.size < msgs.size) {
                    ObjectOutputStream(BufferedOutputStream(FileOutputStream(File(threadDir, headKey(key))))).use { out ->
                        out.writeObject(head)
                    }
                }
            }
        }
    }

    private fun headKey(key: String) = keyFileName(key) + ".head"

    /**
     * Writes the head snapshot for any thread that lacks one.
     *
     * Head files are produced at import, so an archive imported by an older build has none
     * and would never get the fast path. This backfills them once, in the background, so
     * the speed-up works without asking anyone to re-import.
     */
    fun backfillHeads(threads: List<ThreadSummary>) {
        val dir = threadDir ?: return
        for (t in threads) {
            val head = File(dir, headKey(t.key))
            if (head.isFile) continue
            if (t.keptCount <= HEAD_MESSAGES) continue
            val msgs = messages(t.key)
            if (msgs.size <= HEAD_MESSAGES) continue
            runCatching {
                ObjectOutputStream(BufferedOutputStream(FileOutputStream(head))).use { out ->
                    out.writeObject(msgs.take(HEAD_MESSAGES))
                }
            }
        }
    }

    /**
     * The first page of a thread, or null when no head was written (thread fits entirely).
     *
     * Lets the chat screen show something immediately and fill the rest in behind it,
     * instead of blocking on one large deserialisation of the whole conversation.
     */
    fun headMessages(key: String): List<KeptMessage>? {
        messagesByKey[key]?.let { return if (it.size <= HEAD_MESSAGES) it else null }
        val dir = threadDir ?: return null
        val f = File(dir, headKey(key))
        if (!f.isFile) return null
        return runCatching { loadThread(f) }.getOrNull()
    }

    /**
     * Resolve a media URI to a file inside the export, tolerant of the two known
     * quirks: the export root may already be `your_instagram_activity/` (so the URI's
     * leading segment is redundant), and hand-edited exports sometimes move a file
     * so the path no longer matches (fall back to a basename match within the root).
     * Never returns a path outside [root].
     */
    fun mediaFile(uri: String, threadKey: String? = null): File? {
        // The thread's own archive first, then every other root, so a relative media path
        // resolves against the export it actually came from.
        val ordered = buildList {
            threadKey?.let { rootByKey[it] }?.let(::add)
            for (r in roots) if (r !in this) add(r)
            for (r in mediaRoots) if (r !in this) add(r)
        }
        // The thread's own cached media listing is tried FIRST. It is one map hit, whereas
        // a path resolve walks and symlink-checks every component and then throws when the
        // file turns out to be stored under a different name -- which is the common case for
        // exports, and was costing a syscall storm on every single media reference.
        if (threadKey != null) {
            val bare = uri.substringAfterLast('/')
            if (bare.isNotEmpty()) {
                for (base in ordered) {
                    MediaResolver.resolveByBasename(base, threadKey, bare)?.let { return it }
                }
            }
        }

        for (base in ordered) {
            try {
                return MediaResolver.resolve(base, uri)
            } catch (_: Exception) {
            }
            if (uri.startsWith("your_instagram_activity/")) {
                try {
                    return MediaResolver.resolve(base, uri.removePrefix("your_instagram_activity/"))
                } catch (_: Exception) {
                }
            }
        }
        val name = uri.substringAfterLast('/')
        if (name.isEmpty()) return null

        // Slow path, kept for media that genuinely lives somewhere unusual: a whole-tree
        // index across every root. Built lazily, so the common case never pays for it.
        mediaIndex[name]?.let { return it }
        // Some exports name media without any extension (a bare timestamp). Match by the
        // name minus its extension so a URI's ".jpg" still finds an extensionless file,
        // and vice-versa.
        return stemIndex[name.substringBeforeLast('.')]
    }

    private val mediaIndex: Map<String, File> by lazy {
        val map = HashMap<String, File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        // The extra media folders come last so an import root always wins a name clash.
        for (r in roots + mediaRoots) stack.addLast(r to 0)
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

    /** Files keyed by name without its extension, for extensionless media exports. */
    private val stemIndex: Map<String, File> by lazy {
        val map = HashMap<String, File>()
        for ((name, file) in mediaIndex) {
            map.putIfAbsent(name.substringBeforeLast('.'), file)
        }
        map
    }

    /**
     * Who "you" are in a chat, WITHOUT loading its messages.
     *
     * [detectMe] answers the same question but deserialises the whole thread from disk to
     * do it, which is ruinous when the chat list asks for every thread at once. This reads
     * only the participant list already held in memory.
     */
    fun quickSelf(key: String): String? {
        val parts = thread(key)?.participants.orEmpty()
        if (parts.contains("me")) return "me"
        return parts.firstOrNull()?.takeIf { it.isNotBlank() }
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
        /** Messages in the fast head snapshot: a screenful or two of a long chat. */
        const val HEAD_MESSAGES = 200

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
                val roots = (p.rootPaths?.takeIf { it.isNotEmpty() } ?: listOf(p.rootPath))
                    .map { File(it) }
                // Remap the persisted "thread key -> root path" strings back to live Files,
                // dropping any whose export folder has since been removed.
                val rootByKey = p.rootByKeyPaths.orEmpty().mapNotNull { (k, v) ->
                    roots.firstOrNull { it.absolutePath == v }?.let { k to it }
                }.toMap()
                ArchiveIndex(
                    roots = roots,
                    threads = p.threads,
                    messagesByKey = mutableMapOf(),
                    skippedFiles = p.skippedFiles,
                    parseFailures = p.parseFailures,
                    skipReasons = p.skipReasons,
                    threadDir = threadDir,
                    rootByKey = rootByKey,
                    mediaRoots = (p.mediaRootPaths ?: emptyList()).map { File(it) },
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Single-archive convenience; [indexAll] with one root behaves identically. */
        fun index(root: File, onProgress: ((done: Int, total: Int) -> Unit)? = null): ArchiveIndex =
            indexAll(listOf(root), emptyList(), onProgress)

        /**
         * Index one or more exports into a single archive.
         *
         * Thread keys are only namespaced when two exports actually collide, so a plain
         * single-zip import keeps exactly the keys it always had (and stays compatible
         * with the per-chat prefs keyed on them).
         */
        fun indexAll(
            roots: List<File>,
            mediaRoots: List<File> = emptyList(),
            onProgress: ((done: Int, total: Int) -> Unit)? = null,
        ): ArchiveIndex {
            val distinct = roots.filter { it.isDirectory }.distinctBy { it.absolutePath }
            require(distinct.isNotEmpty()) { "no importable folder" }
            val scans = distinct.map { it to Scanner.scan(it) }

            val totalSkipped = scans.sumOf { it.second.totalSkipped }
            val skippedReasons = LinkedHashMap<SkipReason, Int>()
            for ((_, scan) in scans) {
                for ((reason, n) in scan.skipped) skippedReasons[reason] = (skippedReasons[reason] ?: 0) + n
            }

            // Count keys across archives first, so only genuine collisions get namespaced.
            val keyCounts = HashMap<String, Int>()
            for ((_, scan) in scans) for (t in scan.threads) keyCounts[t.key] = (keyCounts[t.key] ?: 0) + 1

            val summaries = ArrayList<ThreadSummary>()
            val byKey = HashMap<String, List<KeptMessage>>()
            val rootByKey = HashMap<String, File>()
            var parseFailures = 0
            val allThreads = scans.flatMap { (r, scan) -> scan.threads.map { r to it } }
            val totalThreads = allThreads.size

            for ((threadIndex, pair) in allThreads.withIndex()) {
                val (threadRoot, t) = pair
                val key = if ((keyCounts[t.key] ?: 0) > 1) "${slugOf(threadRoot)}\u00b7${t.key}" else t.key
                val root = threadRoot
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
                byKey[key] = ingest.kept
                rootByKey[key] = threadRoot
                val lastPreview = previewForChatList(ingest.kept)
                summaries.add(
                    ThreadSummary(
                        key = key,
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
                roots = distinct,
                threads = summaries,
                messagesByKey = byKey.toMutableMap(),
                skippedFiles = totalSkipped,
                parseFailures = parseFailures,
                skipReasons = skippedReasons,
                threadDir = null,
                rootByKey = rootByKey,
                mediaRoots = mediaRoots.filter { it.isDirectory },
            )
        }

        /** A short, filesystem- and display-safe label for an export folder. */
        private fun slugOf(root: File): String =
            root.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "archive" }

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
        /** Added for multi-import; null in snapshots written before it existed. */
        @JvmField val rootPaths: List<String>? = null,
        @JvmField val rootByKeyPaths: Map<String, String>? = null,
        @JvmField val mediaRootPaths: List<String>? = null,
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
