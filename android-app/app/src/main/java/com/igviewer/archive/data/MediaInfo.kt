package com.igviewer.archive.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache

/**
 * Reads duration and a poster frame from a validated local media file using the
 * framework [MediaMetadataRetriever]. Used to show voice-note length and a video
 * thumbnail before anything is played.
 *
 * Every call opens and releases its own retriever (the API is not thread-safe), and
 * results are cached so scrolling a chat never probes the same file twice. Nothing
 * here ever throws: an unreadable/corrupt file simply yields null.
 */
object MediaInfo {

    /** Files whose duration is known, or [NONE] when it could not be read. */
    private const val NONE = -1L
    private val durations = HashMap<String, Long>()

    private val posters = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Duration in ms, or null when the file has none / cannot be read. */
    @Synchronized
    fun durationMs(path: String): Long? {
        durations[path]?.let { return if (it == NONE) null else it }
        val d = withRetriever(path) { r ->
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
        durations[path] = d ?: NONE
        return d
    }

    /** A downscaled poster frame, or null when no frame can be extracted. */
    fun poster(path: String, maxPx: Int): Bitmap? {
        val key = "$path@$maxPx"
        posters.get(key)?.let { return it }
        val bmp = withRetriever(path) { r ->
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withRetriever null
            scaleDown(frame, maxPx)
        }
        if (bmp != null) posters.put(key, bmp)
        return bmp
    }

    private inline fun <T> withRetriever(path: String, block: (MediaMetadataRetriever) -> T?): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            block(r)
        } catch (_: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun scaleDown(src: Bitmap, maxPx: Int): Bitmap {
        val biggest = maxOf(src.width, src.height)
        if (biggest <= maxPx) return src
        val scale = maxPx.toFloat() / biggest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** "0:00" / "1:23" / "1:02:03", or "" when [ms] is unknown. */
    fun formatDuration(ms: Long?): String {
        if (ms == null || ms < 0) return ""
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
