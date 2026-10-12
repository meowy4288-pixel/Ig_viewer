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
