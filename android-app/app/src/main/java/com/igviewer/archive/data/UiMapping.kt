package com.igviewer.archive.data

import com.igviewer.archive.ui.screens.ChatItem
import com.igviewer.archive.ui.screens.MessageItem
import com.igviewer.archive.ui.screens.SearchResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Maps the canonical archive model onto the Compose screens' view models. */
object UiMapping {

    fun chatItems(index: ArchiveIndex): List<ChatItem> = index.threads.map { t ->
        ChatItem(
            key = t.key,
            name = t.title.ifBlank { t.key },
            lastMessage = t.lastPreview,
            date = if (t.lastTsMs > 0) chatDate(t.lastTsMs) else "",
        )
    }

    fun messageItems(index: ArchiveIndex, key: String, selfName: String? = null): List<MessageItem> {
        val me = selfName?.takeIf { it.isNotBlank() } ?: index.detectMe(key)
        val msgs = index.messages(key)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayLabelFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var lastDay = ""
        return msgs.map { m ->
            val day = dayFmt.format(Date(m.tsMs))
            val sep = if (day != lastDay) {
                lastDay = day
                dayLabelFmt.format(Date(m.tsMs))
            } else {
                null
            }
            val images = ArrayList<String>()
            val labels = LinkedHashSet<String>()
            for (ref in m.media) {
                val mime = MediaResolver.mimeFor(ref.uri)
                if (ref.kind in IMAGE_KINDS && mime != null && mime.startsWith("image/")) {
                    val f = index.mediaFile(ref.uri)
                    if (f != null) {
                        images.add(f.absolutePath)
                        continue
                    }
                }
                labels.add(labelFor(ref.kind))
            }
            MessageItem(
                sender = m.sender,
                text = m.content ?: "",
                time = timeFmt.format(Date(m.tsMs)),
                isMe = me.isNotEmpty() && m.sender == me,
                tsMs = m.tsMs,
                isUnsent = m.isUnsent,
                imageFiles = images,
                attachmentLabels = labels.toList(),
                shareLink = m.share?.first?.takeIf { it.isNotBlank() },
                shareText = m.share?.second?.takeIf { it.isNotBlank() },
                reactions = m.reactions.map { it.second },
                daySeparator = sep,
            )
        }
    }

    /**
     * In-memory search across every kept message. Returns at most [limit] hits in
     * thread order; the caller renders the snippet with the query highlighted.
     */
    fun search(index: ArchiveIndex, query: String, limit: Int = 200): List<SearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val dateFmt = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val out = ArrayList<SearchResult>()
        for (t in index.threads) {
            val name = t.title.ifBlank { t.key }
            for ((index, m) in index.messages(t.key).withIndex()) {
                val content = m.content ?: continue
                if (!content.contains(q, ignoreCase = true)) continue
                out.add(
                    SearchResult(
                        key = t.key,
                        msgIndex = index,
                        chatName = name,
                        snippet = content,
                        date = dateFmt.format(Date(m.tsMs)),
                        time = timeFmt.format(Date(m.tsMs)),
                    )
                )
                if (out.size >= limit) return out
            }
        }
        return out
    }

    private val IMAGE_KINDS = setOf("photos", "gifs", "sticker")

    /** Friendly label for a media kind that can't be previewed inline. */
    private fun labelFor(kind: String): String = when (kind) {
        "videos" -> "Video"
        "audio_files" -> "Voice note"
        "files" -> "File"
        "gifs" -> "GIF"
        "sticker" -> "Sticker"
        "photos" -> "Photo"
        else -> kind.replace('_', ' ')
    }

    private fun chatDate(tsMs: Long): String =
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(tsMs))
}
