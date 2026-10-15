package com.igviewer.archive.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Writing media out of the archive.
 *
 * Two directions, both read-only with respect to the export folder:
 *  - [download] copies a file into the folder the user picked with the system picker
 *    (a SAF tree URI), never into the export itself.
 *  - [copyToClipboard] stages a copy in app cache and hands out a content:// URI, so another
 *    app can paste the actual media. The export file itself is never exposed.
 */
object MediaSaver {

    /**
     * Writes [source] into the tree at [treeUri]. Returns the document name it landed under.
     *
     * The name is de-duplicated with a " (2)" suffix rather than overwriting: an export can
     * legitimately contain several media files that resolve to the same display name.
     */
    fun download(context: Context, treeUri: String, source: File): Result<String> = runCatching {
        val resolver = context.contentResolver
        val tree = Uri.parse(treeUri)
        // Re-assert read/write on the persisted grant; the OS drops it after a reinstall.
        try {
            resolver.takePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Grant is already held; carry on.
        }

        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val mime = MediaResolver.mimeFor(source.name) ?: MediaResolver.sniffMime(source) ?: "application/octet-stream"
        val base = uniqueName(resolver, root, source.name)

        val doc = DocumentsContract.createDocument(resolver, root, mime, base)
            ?: error("Could not create $base in the chosen folder")
        resolver.openOutputStream(doc)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: error("Could not open $base for writing")
        base
    }

    /** Finds "name", "name (2)", "name (3)" ... in [parent] and returns the first free one. */
    private fun uniqueName(resolver: android.content.ContentResolver, parent: Uri, name: String): String {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) "" else ".$ext"

        fun taken(candidate: String): Boolean {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getTreeDocumentId(parent))
            return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (c.moveToNext()) if (c.getString(idx) == candidate) return@use true
                    false
                } ?: false
        }

        if (!taken(name)) return name
        var n = 2
        while (n < 1000) {
            val candidate = "$stem ($n)$suffix"
            if (!taken(candidate)) return candidate
            n++
        }
        return "$stem (${System.currentTimeMillis()})$suffix"
    }

    /**
     * Copies [source] into the clipboard as real media, so it can be pasted into another app.
     *
     * The staged copy lives in cache/clipboard and the previous one is cleared first, so the
     * cache cannot grow without bound.
     */
    fun copyToClipboard(context: Context, source: File, mime: String): Result<Unit> = runCatching {
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        val staged = File(dir, source.name)
        source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }

        val uri = FileProviderUri.forFile(context, staged)
        val clip = ClipData.newUri(context.contentResolver, staged.name, uri)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(clip)
    }

    /** Suggestion for the download sheet: the file's display name. */
    fun displayName(source: File): String = source.name.ifBlank { "media" }

    /** Is there a folder chosen yet? */
    fun hasFolder(prefs: Prefs): Boolean = !prefs.downloadTreeUri.isNullOrBlank()
}

/** Builds the content:// URI for a file inside app cache, via the app's FileProvider. */
object FileProviderUri {
    fun forFile(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}