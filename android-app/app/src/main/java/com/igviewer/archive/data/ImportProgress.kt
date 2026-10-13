package com.igviewer.archive.data

import java.util.Locale

/**
 * User-facing progress for an import. The bulk of the time is spent copying or
 * unzipping the export (media barely compresses), not parsing the JSON, so the
 * phase is called out explicitly rather than shown as a generic "indexing".
 */
data class ImportProgress(
    val phase: Phase,
    /** 0f..1f when a total is known, else null for an indeterminate bar. */
    val fraction: Float? = null,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val doneItems: Int = 0,
    val totalItems: Int = 0,
) {
    enum class Phase { COPYING, EXTRACTING, INDEXING }
}

/** Compact human size, e.g. 1.2 GB. UI only. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
}
