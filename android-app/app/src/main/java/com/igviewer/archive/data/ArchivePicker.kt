package com.igviewer.archive.data

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher

/**
 * Picks an archive using the Storage Access Framework.
 *
 * SAF hands back `content://` URIs, which the Rust core cannot open: the platform's
 * ContentResolver is the only thing that can read them. So whatever is chosen is
 * copied into app-private storage first and the Rust core is given a real path.
 *
 * This replaces the Tauri approach entirely. `tauri-plugin-dialog` cannot do this:
 * its `getPathFromUri` returns null for SD cards and cloud providers, and it has no
 * tree-URI branch, so folder picking never worked through it.
 */
class ArchivePicker(private val context: Context) {

    /** Copies a picked tree or file into app storage and returns the local path. */
    fun copyToAppStorage(uri: Uri, isFolder: Boolean, destName: String): String {
        val dest = java.io.File(context.filesDir, "imports/$destName")
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (isFolder) copyTree(uri, uri, dest, 0) else copySingle(uri, dest)
        check(dest.exists()) { "copy produced nothing from $uri" }
        return dest.absolutePath
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
                                        .buildDocumentUriUsingTree(tree, id),
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
    }
}
