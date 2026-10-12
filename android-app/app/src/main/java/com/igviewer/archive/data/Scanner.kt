package com.igviewer.archive.data

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files

/**
 * Port of the Rust core's `scan` module.
 *
 * Layout is fixed and enumerated level by level -- never recursively:
 *
 *   <root>/your_instagram_activity/messages/inbox/<thread>/message_<N>.json
 *
 * The export is treated as strictly read-only.
 */
object Scanner {

    /** Maximum size of a single `message_N.json`, enforced by a bounded read. */
    const val MAX_JSON_BYTES: Long = 32L * 1024 * 1024

    private const val PREFIX = "message_"
    private const val SUFFIX = ".json"
    private const val U32_MAX = 0xFFFFFFFFL

    sealed class FileNo {
        object NotMessage : FileNo()
        object Overflow : FileNo()
        data class Ok(val n: Long) : FileNo()
    }

    /** Extract N from `message_N.json`, rejecting anything not exactly that shape. */
    fun parseFileNumber(name: String): FileNo {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return FileNo.NotMessage
        val rest = name.substring(PREFIX.length, name.length - SUFFIX.length)
        if (rest.isEmpty() || !rest.all { it in '0'..'9' }) return FileNo.NotMessage
        val n = rest.toLongOrNull() ?: return FileNo.Overflow
        if (n > U32_MAX) return FileNo.Overflow
        return FileNo.Ok(n)
    }

    /**
     * Resolve the inbox. Accepts the export root, the `your_instagram_activity`
     * folder, or an archive that unzipped with one or two wrapper folders on top
     * (e.g. `instagram-user-2026-…/your_instagram_activity/messages/inbox`).
     *
     * The search is bounded to at most two directory levels and only lists
     * directories -- it never walks the media tree.
     */
    fun inboxDir(root: File): File {
        findInbox(root)?.let { return it }
        val level1 = root.listFiles().orEmpty()
            .filter { it.isDirectory && !isSymlink(it) }
        for (d in level1) findInbox(d)?.let { return it }
        for (d1 in level1) {
            val level2 = d1.listFiles().orEmpty()
                .filter { it.isDirectory && !isSymlink(it) }
            for (d2 in level2) findInbox(d2)?.let { return it }
        }
        return File(File(File(root, "your_instagram_activity"), "messages"), "inbox")
    }

    private fun findInbox(base: File): File? {
        val direct = File(File(File(base, "your_instagram_activity"), "messages"), "inbox")
        if (direct.isDirectory && !isSymlink(direct)) return direct
        val alt = File(File(base, "messages"), "inbox")
        if (alt.isDirectory && !isSymlink(alt)) return alt
        return null
    }

    private fun isSymlink(f: File): Boolean =
        try {
            Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) {
            false
        }

    /**
     * Read at most [MAX_JSON_BYTES] (+1 to detect overflow) from a file.
     *
     * Returns `Err(TooLarge)` without buffering the whole file.
     */
    fun readBounded(path: File): Result<ByteArray> {
        val cap = MAX_JSON_BYTES + 1
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        try {
            FileInputStream(path).use { input ->
                var total = 0L
                while (true) {
                    val want = minOf((cap - total).coerceAtLeast(1L), buf.size.toLong()).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > MAX_JSON_BYTES) return Result.failure(TooLargeException())
                }
            }
        } catch (_: Exception) {
            return Result.failure(IoException())
        }
        return Result.success(out.toByteArray())
    }

    class TooLargeException : Exception("too-large")
    class IoException : Exception("io-error")

    /** Scan an export root for threads and their message files. */
    fun scan(root: File): ScanResult {
        val resultSkips = LinkedHashMap<SkipReason, Int>()
        val threads = ArrayList<ThreadScan>()

        val inbox = inboxDir(root)
        if (!inbox.isDirectory || isSymlink(inbox)) return ScanResult(emptyList(), resultSkips)

        val threadDirs = inbox.listFiles() ?: return ScanResult(emptyList(), resultSkips)

        for (dir in threadDirs) {
            val key = dir.name
            if (key.isEmpty()) continue
            if (isSymlink(dir)) {
                bump(resultSkips, SkipReason.Symlink); continue
            }
            if (!dir.isDirectory) continue

            val scanSkips = LinkedHashMap<SkipReason, Int>()

            val entries = dir.listFiles()
            if (entries == null) {
                bump(scanSkips, SkipReason.ThreadUnreadable)
                bump(resultSkips, SkipReason.ThreadUnreadable)
                threads.add(ThreadScan(key, emptyList(), scanSkips))
                continue
            }

            // Group by parsed number so a duplicate group can be rejected whole.
            val byNumber = sortedMapOf<Long, File>()
            val duplicates = ArrayList<Long>()

            for (p in entries) {
                when (val parsed = parseFileNumber(p.name)) {
                    is FileNo.NotMessage -> continue // not ours; ignore
                    is FileNo.Overflow -> bump(scanSkips, SkipReason.FileNumberOverflow)
                    is FileNo.Ok -> {
                        if (isSymlink(p)) { bump(scanSkips, SkipReason.Symlink); continue }
                        if (!p.isFile) continue
                        if (p.length() > MAX_JSON_BYTES) { bump(scanSkips, SkipReason.TooLarge); continue }
                        if (byNumber.put(parsed.n, p) != null) duplicates.add(parsed.n)
                    }
                }
            }

            for (n in duplicates) {
                if (byNumber.remove(n) != null) {
                    bump(scanSkips, SkipReason.DuplicateFileNumber)
                    bump(resultSkips, SkipReason.DuplicateFileNumber)
                }
            }

            val files = byNumber.values.toList()
            for ((reason, n) in scanSkips) repeat(n) { bump(resultSkips, reason) }

            if (files.isEmpty() && scanSkips.isEmpty()) continue
            threads.add(ThreadScan(key, files, scanSkips))
        }

        threads.sortBy { it.key }
        return ScanResult(threads, resultSkips)
    }

    private fun bump(v: MutableMap<SkipReason, Int>, r: SkipReason) {
        v[r] = (v[r] ?: 0) + 1
    }
}
