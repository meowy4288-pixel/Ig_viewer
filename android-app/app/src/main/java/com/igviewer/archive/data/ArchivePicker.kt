package com.igviewer.archive.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
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
    fun copyToAppStorage(uri: Uri, isFolder: Boolean, destName: String): String {
        val dest = File(context.filesDir, "imports/$destName")
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (isFolder) copyTree(uri, uri, dest, 0) else copySingle(uri, dest)
        check(dest.exists()) { "copy produced nothing from $uri" }
        return dest.absolutePath
    }

    /** Stream-extract a `.zip` into [destDir]. Returns the dir path. */
    fun extractZip(uri: Uri, destDir: File): String {
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()
        val destCanon = destDir.canonicalFile
        var total = 0L
        var count = 0
        val buf = ByteArray(1 shl 16)
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "cannot open $uri" }
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
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
        check(destDir.isDirectory) { "extract produced nothing from $uri" }
        return destDir.absolutePath
    }

    private fun copySingle(uri: Uri, dest: java.io.File) {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open $uri" }
            dest.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
    }

    private fun copyTree(
        tree: Uri,
        uri: Uri,
        dest: java.io.File,
        depth: Int,
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
            return
        }
        for ((name, child, isDir) in children) {
            // Display names come from the provider; never trust them as path segments.
            if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) continue
            if (isDir) copyTree(tree, child, java.io.File(dest, name), depth + 1)
            else copySingle(child, java.io.File(dest, name))
        }
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_ZIP_FILES = 2_000_000
        const val MAX_ZIP_UNCOMPRESSED = 8L * 1024 * 1024 * 1024
    }
}
