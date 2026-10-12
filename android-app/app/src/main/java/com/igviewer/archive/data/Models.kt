package com.igviewer.archive.data

import java.io.File

/** One media reference, with kind + index-within-kind so duplicates stay distinct. */
data class MediaRef(
    val kind: String,
    val mediaIndex: Int,
    /** Raw URI exactly as it appears in the JSON. Never passed through [Text.fix]. */
    val uri: String,
)

/** A message that survived dedupe, with its canonical position recorded. */
data class KeptMessage(
    val seq: Int,
    val fileNo: Long,
    val idxInFile: Int,
    val tsMs: Long,
    val sender: String,
    val content: String?,
    val isUnsent: Boolean,
    val callDuration: Long?,
    val media: List<MediaRef>,
    val reactions: List<Pair<String, String>>,
)

/** Result of ingesting a single thread. */
data class ThreadIngest(
    val title: String,
    val participants: List<String>,
    val kept: List<KeptMessage>,
    val rawCount: Int,
)

/** Coarse, non-identifying reason a candidate file was not ingested. */
enum class SkipReason(val code: String) {
    NotAMessageFile("not-a-message-file"),
    FileNumberOverflow("file-number-overflow"),
    DuplicateFileNumber("duplicate-file-number"),
    Symlink("symlink"),
    TooLarge("too-large"),
    IoError("io-error"),
    InvalidUtf8("invalid-utf8"),
    MalformedJson("malformed-json"),
    InvalidSchema("invalid-schema"),
    ThreadUnreadable("thread-unreadable"),
}

/** A thread directory's message files (ascending N) and per-thread skips. */
data class ThreadScan(
    val key: String,
    val files: List<File>,
    val skipped: Map<SkipReason, Int>,
)

data class ScanResult(
    val threads: List<ThreadScan>,
    val skipped: Map<SkipReason, Int>,
) {
    val totalSkipped: Int get() = skipped.values.sum()
}

/** Summary of one thread, ready for the chat list. */
data class ThreadSummary(
    val key: String,
    val title: String,
    val participants: List<String>,
    val keptCount: Int,
    val rawCount: Int,
    val firstTsMs: Long,
    val lastTsMs: Long,
)
