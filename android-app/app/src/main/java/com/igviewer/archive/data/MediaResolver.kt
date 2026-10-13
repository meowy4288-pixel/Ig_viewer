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
}
