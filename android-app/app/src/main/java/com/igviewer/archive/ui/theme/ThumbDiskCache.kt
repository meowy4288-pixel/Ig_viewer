package com.igviewer.archive.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest

/**
 * A small on-disk cache of decoded thumbnails.
 *
 * The in-memory cache is lost whenever the process dies, so without this every app launch
 * re-decodes every thumbnail from the original media file. Since these are derived images
 * (already downsampled), storing them is cheap and makes reopening a chat fast.
 *
 * Deliberately hand-rolled rather than pulling in a dependency: the whole cache is a
 * downsampled JPEG per (file, size) pair, keyed by a hash so no path characters ever
 * reach the filesystem.
 */
class ThumbDiskCache(context: Context) {

    private val dir: File = File(context.cacheDir, "thumbs").apply { mkdirs() }

    private fun key(path: String, maxPx: Int): String =
        MessageDigest.getInstance("SHA-1")
            .digest("$path@$maxPx".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun get(path: String, maxPx: Int): ImageBitmap? = runCatching {
        val f = File(dir, key(path, maxPx))
        if (!f.isFile || f.length() == 0L) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
    }.getOrNull()

    fun put(path: String, maxPx: Int, bmp: ImageBitmap) {
        runCatching {
            val f = File(dir, key(path, maxPx))
            // Write to a temp name and rename, so a kill mid-write cannot leave a partial
            // file that would decode to garbage forever.
            val tmp = File(dir, f.name + ".tmp")
            val out = java.io.BufferedOutputStream(java.io.FileOutputStream(tmp))
            val androidBmp = bmp.asAndroidBitmap()
            androidBmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.close()
            if (tmp.renameTo(f)) {
                trim()
            } else {
                tmp.delete()
            }
        }
    }

    /** Bounded by total bytes; the oldest files go first. */
    private fun trim() {
        val maxBytes = 96L * 1024 * 1024
        var total = 0L
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        for (f in files) total += f.length()
        var excess = total - maxBytes
        for (f in files) {
            if (excess <= 0) break
            val len = f.length()
            if (f.delete()) excess -= len
        }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }
}