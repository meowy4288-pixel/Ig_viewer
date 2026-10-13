package com.igviewer.archive.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Picks an archive using the Storage Access Framework.
 *
 * SAF hands back `content://` URIs. Two ways to reach the data:
 *
 *  - For a folder that maps to a real local path (and with the storage permission
 *    the app declares), the folder is read **in place, read-only** -- no 2.5 GB copy.
 *  - Otherwise the tree is copied into app-private storage.
 *
 * A `.zip` is streamed-extracted into app-private storage with zip-slip, backslash,
 * file-count and uncompressed-size guards.
 */
class ArchivePicker(private val context: Context) {

    /** App-private destination the importer always lands in. */
    fun exportDir(): File = File(context.filesDir, "imports/export")

    /**
     * Map a tree URI to a real filesystem path when the provider is local storage.
     * Returns null for cloud/unknown providers so the caller falls back to copying.
     */
    fun directPath(uri: Uri): File? {
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        val sep = docId.indexOf(':')
        if (sep < 0) return null
        val type = docId.substring(0, sep)
        val rel = docId.substring(sep + 1)
        if (rel.isEmpty()) return null
        val base: File = when (type) {
            "primary" -> Environment.getExternalStorageDirectory()
            "raw" -> return File(rel).takeIf { it.isDirectory }
            else -> File("/storage/$type")
        }
        val f = File(base, rel)
        return f.takeIf { it.isDirectory }
    }

    /** Copies a picked tree or file into app storage and returns the local path. */
    fun copyToAppStorage(
        uri: Uri,
        isFolder: Boolean,
        destName: String,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ): String {
        val dest = File(context.filesDir, "imports/$destName")
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (isFolder) copyTree(uri, uri, dest, 0, IntArray(1), onProgress)
        else copySingle(uri, dest, onProgress)
        check(dest.exists()) { "copy produced nothing from $uri" }
        return dest.absolutePath
    }

    /**
     * Extract a `.zip` into [destDir]. Returns the dir path.
     *
     * Streaming via [ZipInputStream] is the default (no temp copy). Some writers --
     * notably phone "compress", which streams and cannot seek -- emit entries whose
     * sizes live in a data descriptor or ZIP64, which [ZipInputStream] mis-reads and
     * then fails with "unexpected end of zlib input stream". On that failure we retry
     * through the central directory with [java.util.zip.ZipFile], which knows the exact
     * per-entry sizes; it needs a seekable copy, so we spill the zip to cache first.
     */
    fun extractZip(
        uri: Uri,
        destDir: File,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ): String {
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        // Progress tracks bytes consumed from the source stream against the zip's
        // on-disk size -- a good proxy, since extracting media reads roughly the whole
        // input. Reported at most every PROGRESS_STEP bytes to avoid spamming the UI.
        val srcSize = uriSize(uri)
        val report: (Long) -> Unit = { counted ->
            onProgress?.invoke(
                ImportProgress(
                    phase = ImportProgress.Phase.EXTRACTING,
                    fraction = if (srcSize > 0) (counted.toFloat() / srcSize).coerceIn(0f, 1f) else null,
                    doneBytes = counted,
                    totalBytes = if (srcSize > 0) srcSize else 0L,
                )
            )
        }
        // "Unexpected end of ZLIB input stream" surfaces as EOFException, while a bad
        // method/size surfaces as ZipException. Both mean ZipInputStream could not make
        // sense of a streaming/data-descriptor zip, so either triggers the ZipFile retry.
        val inflateFailed = try {
            extractStreaming(uri, destDir, report)
            false
        } catch (e: java.io.EOFException) {
            true
        } catch (e: java.util.zip.ZipException) {
            true
        }
        if (inflateFailed) {
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()
            extractViaZipFile(uri, destDir, report)
        }
        check(destDir.isDirectory) { "extract produced nothing from $uri" }
        return destDir.absolutePath
    }

    private fun extractStreaming(uri: Uri, destDir: File, report: (Long) -> Unit) {
        val destCanon = destDir.canonicalFile
        var total = 0L
        var count = 0
        val buf = ByteArray(1 shl 16)
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "cannot open $uri" }
            val counted = CountingInputStream(BufferedInputStream(raw), PROGRESS_STEP, report)
            ZipInputStream(counted).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (name.contains('\\')) throw IOException("backslash in zip member")
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                        throw IOException("zip-slip member")
                    }
                    val out = File(destDir, name).canonicalFile
                    if (out != destCanon && !out.path.startsWith(destCanon.path + File.separator)) {
                        throw IOException("zip member escapes destination")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                        zis.closeEntry()
                        continue
                    }
                    count++
                    if (count > MAX_ZIP_FILES) throw IOException("too many zip members")
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_ZIP_UNCOMPRESSED) throw IOException("zip too large")
                            o.write(buf, 0, n)
                        }
                    }
                    zis.closeEntry()
                }
            }
        }
    }

    private fun extractViaZipFile(uri: Uri, destDir: File, report: (Long) -> Unit) {
        val tmp = File(context.cacheDir, "import.zip")
        tmp.delete()
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "cannot open $uri" }
            val counted = CountingInputStream(BufferedInputStream(raw), PROGRESS_STEP, report)
            tmp.outputStream().use { o -> counted.copyTo(o, 1 shl 16) }
            counted.close() // emits the final 100% reading
        }
        try {
            java.util.zip.ZipFile(tmp).use { zip -> extractEntries(zip, destDir) }
        } finally {
            tmp.delete()
        }
    }

    private fun extractEntries(zip: java.util.zip.ZipFile, destDir: File) {
        val destCanon = destDir.canonicalFile
        var total = 0L
        var count = 0
        val buf = ByteArray(1 shl 16)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (name.contains('\\')) throw IOException("backslash in zip member")
            if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                throw IOException("zip-slip member")
            }
            val out = File(destDir, name).canonicalFile
            if (out != destCanon && !out.path.startsWith(destCanon.path + File.separator)) {
                throw IOException("zip member escapes destination")
            }
            if (entry.isDirectory) {
                out.mkdirs()
                continue
            }
            count++
            if (count > MAX_ZIP_FILES) throw IOException("too many zip members")
            out.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { o ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP_UNCOMPRESSED) throw IOException("zip too large")
                        o.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun copySingle(
        uri: Uri,
        dest: java.io.File,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ) {
        dest.parentFile?.mkdirs()
        val size = uriSize(uri)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open $uri" }
            val counted = CountingInputStream(BufferedInputStream(input), PROGRESS_STEP) { done ->
                onProgress?.invoke(
                    ImportProgress(
                        phase = ImportProgress.Phase.COPYING,
                        fraction = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else null,
                        doneBytes = done,
                        totalBytes = if (size > 0) size else 0L,
                    )
                )
            }
            dest.outputStream().use { out -> counted.copyTo(out, 1 shl 16) }
            counted.close()
        }
    }

    private fun copyTree(
        tree: Uri,
        uri: Uri,
        dest: File,
        depth: Int,
        fileCounter: IntArray,
        onProgress: ((ImportProgress) -> Unit)? = null,
    ) {
        require(depth <= MAX_DEPTH) { "tree too deep" }
        dest.mkdirs()
        val docId = runCatching {
            android.provider.DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return

        val children = runCatching {
            context.contentResolver.query(
                android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                val idC = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nC = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mC = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                buildList {
                    if (idC >= 0) {
                        while (c.moveToNext()) {
                            val id = c.getLong(idC)
                            add(
                                Triple(
                                    (if (nC >= 0) c.getString(nC) else id.toString())
                                        ?: id.toString(),
                                    android.provider.DocumentsContract
                                        .buildDocumentUriUsingTree(tree, id.toString()),
                                    (if (mC >= 0) c.getString(mC) else "") ==
                                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                                )
                            )
                        }
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())

        if (children.isEmpty()) {
            copySingle(uri, dest)
            fileCounter[0]++
            onProgress?.invoke(ImportProgress(ImportProgress.Phase.COPYING, doneItems = fileCounter[0]))
            return
        }
        for ((name, child, isDir) in children) {
            // Display names come from the provider; never trust them as path segments.
            if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) continue
            if (isDir) {
                copyTree(tree, child, java.io.File(dest, name), depth + 1, fileCounter, onProgress)
            } else {
                copySingle(child, java.io.File(dest, name))
                fileCounter[0]++
                onProgress?.invoke(ImportProgress(ImportProgress.Phase.COPYING, doneItems = fileCounter[0]))
            }
        }
    }

    /** Total byte size a content URI advertises, or -1 when the provider won't say. */
    private fun uriSize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst() && !c.isNull(i)) c.getLong(i) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /**
     * Passes bytes through unchanged while reporting cumulative reads, but only once
     * per [threshold] so a fast stream cannot flood the UI. [close] always reports the
     * final total.
     */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val threshold: Long,
        private val onBytes: (Long) -> Unit,
    ) : InputStream() {
        private var count = 0L
        private var last = 0L

        private fun bump(delta: Int) {
            count += delta
            if (count - last >= threshold) {
                last = count
                onBytes(count)
            }
        }

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) bump(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bump(n)
            return n
        }

        override fun available(): Int = delegate.available()

        override fun close() {
            onBytes(count)
            delegate.close()
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_ZIP_FILES = 2_000_000
        const val MAX_ZIP_UNCOMPRESSED = 8L * 1024 * 1024 * 1024

        /** Minimum bytes between progress callbacks, so a fast stream cannot flood the UI. */
        const val PROGRESS_STEP = 4L * 1024 * 1024
    }
}
