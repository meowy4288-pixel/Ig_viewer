package com.igviewer.archive.data

import com.igviewer.archive.ui.screens.ChatItem
import com.igviewer.archive.ui.screens.MessageItem
import com.igviewer.archive.ui.screens.SearchResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Maps the canonical archive model onto the Compose screens' view models. */
object UiMapping {

    fun chatItems(
        index: ArchiveIndex,
        renameOf: (String, String) -> String = { _, n -> n },
        avatarOf: (String, String) -> String? = { _, _ -> null },
        /** Who "you" are in a given chat, so the title can leave you out of it. */
        selfOf: (String) -> String? = { null },
    ): List<ChatItem> =
        index.threads.map { t ->
        // Renames are applied to the raw participant list first, so a renamed person wins
        // over the export's own title ordering. Both lookups are per chat.
        // "You" is dropped from the title: a DM should be called by the other person, not
        // "you and them".
        val me = selfOf(t.key)
        val shown = t.participants
            .filter { it != me }
            .map { renameOf(t.key, it) }
            .filter { it.isNotBlank() }
        val title = when {
            shown.isEmpty() -> t.title.ifBlank { t.key }
            shown.size == 1 -> shown.first()
            else -> shown.joinToString(", ")
        }
        ChatItem(
            key = t.key,
            name = title.ifBlank { t.key },
            // Only ever the other person's photo: a DM row must not show your own.
            avatarPath = t.participants.filter { it != me }.firstNotNullOfOrNull { avatarOf(t.key, it) },
            lastMessage = t.lastPreview,
            date = if (t.lastTsMs > 0) chatDate(t.lastTsMs) else "",
        )
    }

    /**
     * Maps a slice of a thread, resumably.
     *
     * Returns the items for `[from, from + count)` plus the day string to pass as
     * [fromDay] on the next call, so day separators stay correct across chunk boundaries.
     * This is what lets a chat render its first screen before the whole conversation has
     * been mapped -- and mapped media, which is the expensive part.
     */
    fun messageChunk(
        index: ArchiveIndex,
        key: String,
        msgs: List<KeptMessage>,
        from: Int,
        count: Int,
        selfName: String? = null,
        renameOf: (String, String) -> String = { _, n -> n },
        avatarOf: (String, String) -> String? = { _, _ -> null },
        fromDay: String = "",
    ): Pair<List<MessageItem>, String> {
        val out = ArrayList<MessageItem>(count)
        var day = fromDay
        mapRange(index, key, msgs, from, count, selfName, renameOf, avatarOf, fromDay) { item, after ->
            out.add(item)
            day = after
        }
        return out to day
    }

    fun messageItems(
        index: ArchiveIndex,
        key: String,
        selfName: String? = null,
        renameOf: (String, String) -> String = { _, n -> n },
        avatarOf: (String, String) -> String? = { _, _ -> null },
        /** Read a specific slice instead of the whole thread, for progressive rendering. */
        source: List<KeptMessage>? = null,
    ): List<MessageItem> {
        val msgs = source ?: index.messages(key)
        val out = ArrayList<MessageItem>(msgs.size)
        mapRange(index, key, msgs, 0, msgs.size, selfName, renameOf, avatarOf, "") { item, _ ->
            out.add(item)
        }
        return out
    }

    /**
     * The per-message mapping, shared by the whole-thread and chunked entry points.
     *
     * [emit] receives each built item and the running day string.
     */
    private fun mapRange(
        index: ArchiveIndex,
        key: String,
        msgs: List<KeptMessage>,
        from: Int,
        count: Int,
        selfName: String?,
        renameOf: (String, String) -> String,
        avatarOf: (String, String) -> String?,
        startDay: String,
        emit: (MessageItem, String) -> Unit,
    ) {
        val me = selfName?.takeIf { it.isNotBlank() } ?: index.quickSelf(key)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayLabelFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var lastDay = startDay
        val end = minOf(msgs.size, from + count)
        for (i in from until end) {
            val m = msgs[i]
            val day = dayFmt.format(Date(m.tsMs))
            val sep = if (day != lastDay) {
                lastDay = day
                dayLabelFmt.format(Date(m.tsMs))
            } else {
                null
            }
            val images = ArrayList<String>()
            val audios = ArrayList<String>()
            val videos = ArrayList<String>()
            val labels = LinkedHashSet<String>()
            for (ref in m.media) {
                // Only kinds we can render inline need the file resolved.
                if (ref.kind !in IMAGE_KINDS && ref.kind != "audio_files" && ref.kind != "videos") {
                    labels.add(labelFor(ref.kind))
                    continue
                }
                val file = index.mediaFile(ref.uri, key)
                if (file == null) {
                    labels.add(labelFor(ref.kind))
                    continue
                }
                // Some exports name media with a bare timestamp and no extension; fall back
                // to sniffing the bytes so those still render instead of becoming a chip.
                val mime = MediaResolver.mimeFor(ref.uri) ?: MediaResolver.sniffMime(file)
                when {
                    ref.kind in IMAGE_KINDS && mime != null && mime.startsWith("image/") ->
                        images.add(file.absolutePath)
                    ref.kind == "audio_files" && isAudioContainer(mime) ->
                        audios.add(file.absolutePath)
                    ref.kind == "videos" && mime != null && mime.startsWith("video/") ->
                        videos.add(file.absolutePath)
                    else -> labels.add(labelFor(ref.kind))
                }
            }
            emit(
                MessageItem(
                    sender = renameOf(key, m.sender),
                    text = m.content ?: "",
                    time = timeFmt.format(Date(m.tsMs)),
                    isMe = !me.isNullOrEmpty() && m.sender == me,
                    tsMs = m.tsMs,
                    isUnsent = m.isUnsent,
                    callDurationMs = m.callDuration,
                    imageFiles = images,
                    audioFiles = audios,
                    videoFiles = videos,
                    attachmentLabels = labels.toList(),
                    shareLink = m.share?.first?.takeIf { it.isNotBlank() },
                    shareText = m.share?.second?.takeIf { it.isNotBlank() },
                    reactions = m.reactions.map { it.second },
                    daySeparator = sep,
                    msgIndex = i,
                    rawSender = m.sender,
                    avatarPath = avatarOf(key, m.sender),
                ),
                lastDay,
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

    /**
     * Voice notes are stored by Instagram as AAC audio inside an `.mp4` (sometimes `.mov`)
     * container, so [MediaResolver] reports them as a video MIME type. Accept both so a real audio
     * file and an mp4-container audio file are both treated as playable audio.
     */
    internal fun isAudioContainer(mime: String?): Boolean = when {
        mime == null -> false
        mime.startsWith("audio/") -> true
        mime == "video/mp4" -> true
        mime == "video/quicktime" -> true
        else -> false
    }

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
        DAY_MONTH.format(java.time.Instant.ofEpochMilli(tsMs))

    /**
     * Formatters are built once, not per row.
     *
     * SimpleDateFormat has to parse its pattern and load locale tables on construction, so
     * building one per chat row was a measurable chunk of every boot. java.time formatters
     * are immutable and thread-safe, so one instance can be reused.
     */
    private val DAY_MONTH: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault())
            .withZone(java.time.ZoneId.systemDefault())
}
