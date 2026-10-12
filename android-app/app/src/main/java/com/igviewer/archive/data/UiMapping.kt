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
        val msgs = index.messages(t.key)
        ChatItem(
            key = t.key,
            name = t.title.ifBlank { t.key },
            lastMessage = preview(msgs.lastOrNull()),
            date = if (t.lastTsMs > 0) chatDate(t.lastTsMs) else "",
        )
    }

    fun messageItems(index: ArchiveIndex, key: String): List<MessageItem> {
        val me = index.detectMe(key)
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
            val images = m.media
                .filter { it.kind in IMAGE_KINDS }
                .mapNotNull { ref ->
                    val mime = MediaResolver.mimeFor(ref.uri)
                    if (mime == null || !mime.startsWith("image/")) return@mapNotNull null
                    index.mediaFile(ref.uri)?.absolutePath
                }
            MessageItem(
                sender = m.sender,
                text = m.content ?: "",
                time = timeFmt.format(Date(m.tsMs)),
                isMe = me.isNotEmpty() && m.sender == me,
                isUnsent = m.isUnsent,
                hasImage = m.media.any { it.kind in IMAGE_KINDS },
                hasVoice = m.media.any { it.kind in VOICE_KINDS },
                imageFiles = images,
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
            for (m in index.messages(t.key)) {
                val content = m.content ?: continue
                if (!content.contains(q, ignoreCase = true)) continue
                out.add(
                    SearchResult(
                        key = t.key,
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

    private val IMAGE_KINDS = setOf("photos", "videos", "gifs", "sticker")
    private val VOICE_KINDS = setOf("audio_files", "files")

    private fun preview(m: KeptMessage?): String {
        if (m == null) return ""
        if (m.isUnsent) return "Message unsent"
        val c = m.content?.takeIf { it.isNotBlank() }
        if (c != null) return c
        return when {
            m.media.any { it.kind in IMAGE_KINDS } -> "Photo"
            m.media.any { it.kind in VOICE_KINDS } -> "Voice note"
            m.media.isNotEmpty() -> "Attachment"
            else -> ""
        }
    }

    private fun chatDate(tsMs: Long): String =
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(tsMs))
}
