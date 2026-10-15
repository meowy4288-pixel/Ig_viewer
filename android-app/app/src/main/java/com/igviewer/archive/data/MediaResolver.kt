package com.igviewer.archive.data

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Port of the Rust core's `media` + `protocol` MIME allowlist.
 *
 * A media URI inside the export JSON is untrusted. Resolution rejects traversal
 * syntactically before any I/O, then walks every path component refusing symlinks
 * (a symlinked *parent* would defeat a final-component check alone), and finally
 * re-verifies the canonical path stays inside the export root.
 */
object MediaResolver {

    enum class MediaStatus(val code: String) {
        Ok("ok"),
        TraversalRejected("traversal-rejected"),
        SymlinkRejected("symlink-rejected"),
        OutsideExport("outside-export"),
        Missing("missing"),
        Unreadable("unreadable"),
    }

    class MediaException(val status: MediaStatus) : Exception(status.code)

    /** MIME types the app will render. Script-executing types (html, svg+xml) are absent. */
    private val ALLOWED_MIME = listOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "opus" to "audio/ogg",
    )

    /** MIME type for an export-relative URI, or null if not renderable. */
    fun mimeFor(uri: String): String? {
        val dot = uri.lastIndexOf('.')
        if (dot < 0) return null
        val ext = uri.substring(dot + 1).lowercase()
        return ALLOWED_MIME.firstOrNull { it.first == ext }?.second
    }

    /**
     * Detect a renderable MIME type from a file's leading bytes, for exports whose media
     * files have no extension (Instagram sometimes names them with a bare timestamp).
     * Returns null when the content is not a recognised image/audio/video/container.
     */
    fun sniffMime(file: File): String? {
        // The export is read-only, so a file's first bytes never change. Without this, every
        // extensionless media reference re-opened and re-read the file from disk.
        val key = sniffCacheKey(file)
        if (key != null) {
            val hit = sniffCache[key]
            if (hit != null) return if (hit == SNIFF_MISS) null else hit
        }
        val detected = sniffBytes(file)
        if (key != null) sniffCache[key] = detected ?: SNIFF_MISS
        return detected
    }

    private fun sniffCacheKey(file: File): String? = try {
        val t = file.lastModified()
        if (t <= 0L) null else file.absolutePath + "@" + t
    } catch (_: Exception) {
        null
    }

    private fun sniffBytes(file: File): String? {
        val head = ByteArray(32)
        val n = try {
            file.inputStream().use { it.read(head) }
        } catch (_: IOException) {
            return null
        }
        fun ascii(off: Int, len: Int): String =
            if (off + len <= n) String(head, off, len, Charsets.US_ASCII) else ""

        if (n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (n >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (ascii(0, 4) == "GIF8") return "image/gif"
        if (ascii(0, 4) == "RIFF") {
            when (ascii(8, 4)) {
                "WEBP" -> return "image/webp"
                "WAVE" -> return "audio/wav"
                "AVI " -> return "video/x-msvideo"
            }
        }
        if (n >= 2 && head[0] == 0x42.toByte() && head[1] == 0x4D.toByte()) return "image/bmp"
        if (ascii(4, 4) == "ftyp") {
            val brand = ascii(8, 4)
            return when {
                brand.startsWith("qt") -> "video/quicktime"
                brand in setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1") ->
                    "image/heic"
                brand.startsWith("M4A") -> "audio/mp4"
                brand.startsWith("M4V") -> "video/mp4"
                else -> "video/mp4"
            }
        }
        if (ascii(0, 3) == "ID3") return "audio/mpeg"
        if (n >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) return "audio/mpeg"
        if (ascii(0, 4) == "OggS") return "audio/ogg"
        if (ascii(0, 4) == "fLaC") return "audio/flac"
        if (n >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()
        ) {
            return "video/webm"
        }
        return null
    }

    /** Validate a URI and resolve it to a file inside [root]. */
    fun resolve(root: File, uri: String): File {
        if (uri.isEmpty() || uri.startsWith('/') || uri.contains('\u0000')) {
            throw MediaException(MediaStatus.TraversalRejected)
        }
        val segs = uri.split('/')
        if (segs.any { it == ".." || it == "." }) {
            throw MediaException(MediaStatus.TraversalRejected)
        }

        val rootCanon = try {
            root.canonicalFile
        } catch (_: IOException) {
            throw MediaException(MediaStatus.Missing)
        }

        val parts = segs.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw MediaException(MediaStatus.TraversalRejected)

        var current = rootCanon
        parts.forEachIndexed { i, seg ->
            current = File(current, seg)
            if (isSymlink(current)) throw MediaException(MediaStatus.SymlinkRejected)
            val last = i == parts.size - 1
            if (last) {
                if (!current.isFile) throw MediaException(MediaStatus.Missing)
            } else {
                if (!current.isDirectory) throw MediaException(MediaStatus.Missing)
            }
        }

        val canon = try {
            current.canonicalFile
        } catch (_: IOException) {
            throw MediaException(MediaStatus.Missing)
        }
        if (!canon.path.startsWith(rootCanon.path + File.separator) && canon != rootCanon) {
            throw MediaException(MediaStatus.OutsideExport)
        }
        return canon
    }

    /** Read up to [max] bytes of a validated media file. */
    fun readCapped(root: File, uri: String, max: Long): ByteArray {
        val file = resolve(root, uri)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        try {
            file.inputStream().use { input ->
                var total = 0L
                while (total < max) {
                    val want = minOf(buf.size.toLong(), max - total).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
            }
        } catch (_: IOException) {
            throw MediaException(MediaStatus.Unreadable)
        }
        return out.toByteArray()
    }

    private fun isSymlink(f: File): Boolean =
        try {
            Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) {
            false
        }

    /**
     * Locate the inbox directory, bounded to a couple of wrapper levels.
     *
     * Cheap by construction: it only ever lists directories and never walks media.
     */
    fun findInbox(root: File): File? {
        val direct = listOf(
            File(root, "inbox"),
            File(root, "messages/inbox"),
            File(root, "your_instagram_activity/messages/inbox"),
        )
        for (d in direct) if (d.isDirectory && !isSymlink(d)) return d
        // One or two wrapper folders, e.g. instagram-user-2026-…/your_instagram_activity/…
        for (l1 in (root.listFiles().orEmpty()).filter { it.isDirectory && !isSymlink(it) }) {
            for (cand in listOf(
                File(l1, "messages/inbox"),
                File(l1, "your_instagram_activity/messages/inbox"),
                File(l1, "inbox"),
            )) {
                if (cand.isDirectory && !isSymlink(cand)) return cand
            }
            for (l2 in (l1.listFiles().orEmpty()).filter { it.isDirectory && !isSymlink(it) }) {
                val deep = File(l2, "your_instagram_activity/messages/inbox")
                if (deep.isDirectory && !isSymlink(deep)) return deep
                val mid = File(l2, "messages/inbox")
                if (mid.isDirectory && !isSymlink(mid)) return mid
            }
        }
        return null
    }

    /**
     * Find a media file by bare name inside ONE thread's own media folders.
     *
     * This is the cheap path that replaces walking every media file in the archive. An
     * export commonly names a file with a bare timestamp and no extension while the JSON
     * URI still says ".jpg", so a straight path resolve misses and a name lookup finds it.
     *
     * Bounded and safe: [name] must be a bare filename, only the three known media
     * subfolders are listed, and symlinks are refused. Returns null if it is not there.
     */
    fun resolveByBasename(root: File, threadKey: String, name: String): File? {
        if (name.isEmpty() || name.length > 255) return null
        if (name.contains('/') || name.contains('\\') || name.contains('\u0000')) return null
        if (name == "." || name == "..") return null
        // Thread keys may be namespaced as "<archive>\u00b7<thread>" when several exports
        // were merged; the folder on disk is still the bare thread name.
        val thread = threadKey.substringAfter('\u00b7')
        val inbox = findInbox(root) ?: return null
        val dir = File(inbox, thread)
        if (!dir.isDirectory || isSymlink(dir)) return null

        // Exact name first: one map hit covers the ordinary case, so the extension
        // variants below only run when the export named the file differently.
        val byName = threadMediaMap(root, threadKey) ?: return null

        fun hit(key: String): File? {
            val f = byName[key] ?: return null
            return if (f.isFile && !isSymlink(f)) f else null
        }

        hit(name)?.let { return it }

        // Exports disagree about which side carries the extension: the file on disk may be
        // a bare timestamp while the URI says ".jpg", or the reverse.
        val stem = name.substringBeforeLast('.', name)
        if (stem.isNotEmpty() && stem != name) {
            hit(stem)?.let { return it }
            for (ext in KNOWN_MEDIA_EXTENSIONS) {
                hit("$stem.$ext")?.let { return it }
            }
        }
        return null
    }

    /** Placeholder for "sniffed, no type", so misses are cached too. */
    private const val SNIFF_MISS = "\u0000miss"

    private val sniffCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Cached listing of one thread's media folders, as file name -> file.
     *
     * The lookup below used to list all three directories and scan them linearly for EVERY
     * media reference. With a few thousand media in a chat that is tens of thousands of
     * comparisons per lookup, which is what made opening a chat take seconds. Built once
     * per (archive, thread); lookups then cost one map hit.
     */
    private val threadMediaCache =
        java.util.concurrent.ConcurrentHashMap<String, Map<String, File>>()

    private val KNOWN_MEDIA_EXTENSIONS =
        listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "mp4", "mov", "m4a", "mp3", "ogg")

    private fun threadMediaMap(root: File, threadKey: String): Map<String, File>? {
        val thread = threadKey.substringAfter('\u00b7')
        val cacheKey = root.absolutePath + "\u0000" + thread
        threadMediaCache[cacheKey]?.let { return it }
        val inbox = findInbox(root) ?: return null
        val dir = File(inbox, thread)
        if (!dir.isDirectory || isSymlink(dir)) return null
        val map = HashMap<String, File>()
        for (sub in listOf("photos", "videos", "audio")) {
            val d = File(dir, sub)
            if (!d.isDirectory || isSymlink(d)) continue
            val entries = d.listFiles() ?: continue
            for (f in entries) {
                if (f.isFile && !isSymlink(f)) map.putIfAbsent(f.name, f)
            }
        }
        threadMediaCache[cacheKey] = map
        return map
    }
}
