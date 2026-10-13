package com.igviewer.archive.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Port of the Rust core's `ingest` module. Two contracts, both matching
 * `fake-data/expected.json`:
 *
 * **Dedupe key** = (timestampMs, senderNameRaw, contentRaw, mediaUris) with all
 * values taken BEFORE [Text.fix]. `mediaUris` concatenates six kinds in a fixed
 * order -- photos, videos, audio_files, gifs, sticker, files -- each kind's entries
 * in on-disk order. It is a typed list, never a joined string.
 *
 * **Order** = (timestampMs ASC, seq DESC). `seq` is a per-thread counter over RAW
 * encounters, assigned before dedupe (dropped entries still consume a seq). Because
 * each `message_N.json` stores newest-first and files are read in ascending N, a
 * higher seq at the same timestamp displays first.
 */
object Ingest {

    val MEDIA_KINDS = listOf("photos", "videos", "audio_files", "gifs", "sticker", "files")

    /** One message exactly as it appears on disk (all text still mojibaked). */
    class RawMessage(
        val senderName: String,
        val timestampMs: Long,
        val content: String?,
        val isUnsent: Boolean?,
        val callDuration: Long?,
        val photos: List<MediaItem>?,
        val videos: List<MediaItem>?,
        val audioFiles: List<MediaItem>?,
        val gifs: List<MediaItem>?,
        /** In Instagram's schema `sticker` is a single object, not an array. */
        val sticker: MediaItem?,
        val files: List<MediaItem>?,
        val reactions: List<Reaction>?,
        /** A shared post/reel: link + caption. Not part of the dedupe key. */
        val share: Share?,
    )

    class MediaItem(val uri: String)
    class Reaction(val actor: String, val reaction: String)
    class Share(val link: String, val shareText: String)

    class ParsedThread(
        val title: String,
        val participants: List<String>,
        val messages: List<RawMessage>,
    )

    private data class DedupeKey(
        val tsMs: Long,
        val sender: String,
        val content: String?,
        val uris: List<String>,
    )

    /** Ordered URI vector for the dedupe key. */
    fun mediaUris(m: RawMessage): List<String> {
        val out = ArrayList<String>()
        for (kind in MEDIA_KINDS) {
            when (kind) {
                "photos" -> m.photos?.forEach { out.add(it.uri) }
                "videos" -> m.videos?.forEach { out.add(it.uri) }
                "audio_files" -> m.audioFiles?.forEach { out.add(it.uri) }
                "gifs" -> m.gifs?.forEach { out.add(it.uri) }
                "sticker" -> m.sticker?.let { out.add(it.uri) }
                "files" -> m.files?.forEach { out.add(it.uri) }
            }
        }
        return out
    }

    /** Every media reference with its kind and index, in key order. */
    fun mediaRefs(m: RawMessage): List<MediaRef> {
        val out = ArrayList<MediaRef>()
        for (kind in MEDIA_KINDS) {
            when (kind) {
                "photos" -> pushRefs(out, kind, m.photos)
                "videos" -> pushRefs(out, kind, m.videos)
                "audio_files" -> pushRefs(out, kind, m.audioFiles)
                "gifs" -> pushRefs(out, kind, m.gifs)
                "sticker" -> m.sticker?.let { out.add(MediaRef(kind, 0, it.uri)) }
                "files" -> pushRefs(out, kind, m.files)
            }
        }
        return out
    }

    private fun pushRefs(out: MutableList<MediaRef>, kind: String, items: List<MediaItem>?) {
        items?.forEachIndexed { i, it -> out.add(MediaRef(kind, i, it.uri)) }
    }

    /**
     * Parse one file's text into raw fields.
     *
     * @throws JSONException when the text is not valid JSON.
     */
    fun parseThreadFile(text: String): ParsedThread {
        val root = JSONObject(text)
        val title = root.optString("title", "")
        val participants = ArrayList<String>()
        root.optJSONArray("participants")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                participants.add(o.optString("name", ""))
            }
        }
        val messages = ArrayList<RawMessage>()
        root.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                messages.add(parseMessage(o))
            }
        }
        return ParsedThread(title, participants, messages)
    }

    private fun parseMessage(o: JSONObject): RawMessage = RawMessage(
        senderName = o.optString("sender_name", ""),
        timestampMs = o.optLong("timestamp_ms", 0L),
        content = strOrNull(o, "content"),
        isUnsent = if (o.has("is_unsent") && !o.isNull("is_unsent")) o.optBoolean("is_unsent") else null,
        callDuration = if (o.has("call_duration") && !o.isNull("call_duration")) o.optLong("call_duration") else null,
        photos = mediaList(o.optJSONArray("photos")),
        videos = mediaList(o.optJSONArray("videos")),
        audioFiles = mediaList(o.optJSONArray("audio_files")),
        gifs = mediaList(o.optJSONArray("gifs")),
        sticker = o.optJSONObject("sticker")?.let { MediaItem(it.optString("uri", "")) },
        files = mediaList(o.optJSONArray("files")),
        reactions = reactionList(o.optJSONArray("reactions")),
        share = o.optJSONObject("share")?.let {
            Share(it.optString("link", ""), it.optString("share_text", ""))
        },
    )

    private fun mediaList(arr: JSONArray?): List<MediaItem>? {
        if (arr == null) return null
        val out = ArrayList<MediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(MediaItem(o.optString("uri", "")))
        }
        return out
    }

    private fun reactionList(arr: JSONArray?): List<Reaction>? {
        if (arr == null) return null
        val out = ArrayList<Reaction>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Reaction(o.optString("actor", ""), o.optString("reaction", "")))
        }
        return out
    }

    private fun strOrNull(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.optString(key) else null

    /**
     * Apply the canonical dedupe and display order to one thread.
     *
     * [files] must already be in ascending file number; each entry is
     * (fileNo, parsed messages in on-disk order).
     */
    fun ingestThread(
        title: String,
        participants: List<String>,
        files: List<Pair<Long, List<RawMessage>>>,
    ): ThreadIngest {
        val kept = ArrayList<KeptMessage>()
        val seenEarlier = HashSet<DedupeKey>()
        var rawCount = 0
        var seq = 0

        for ((fileNo, messages) in files) {
            // Keys introduced by THIS file, merged only after the file completes, so
            // identical messages inside one file are all kept.
            val here = HashSet<DedupeKey>()
            messages.forEachIndexed { idx, m ->
                val key = DedupeKey(m.timestampMs, m.senderName, m.content, mediaUris(m))
                rawCount++
                if (seenEarlier.contains(key)) {
                    seq++
                    return@forEachIndexed
                }
                here.add(key)
                kept.add(
                    KeptMessage(
                        seq = seq,
                        fileNo = fileNo,
                        idxInFile = idx,
                        tsMs = m.timestampMs,
                        sender = Text.fix(m.senderName),
                        content = m.content?.let { Text.fix(it) },
                        isUnsent = m.isUnsent ?: false,
                        callDuration = m.callDuration,
                        media = mediaRefs(m),
                        reactions = m.reactions.orEmpty().map { Text.fix(it.actor) to Text.fix(it.reaction) },
                        share = m.share?.let { it.link to Text.fix(it.shareText) },
                    )
                )
                seq++
            }
            seenEarlier.addAll(here)
        }

        // Display order: oldest first; ties break on higher seq first.
        kept.sortWith(compareBy({ it.tsMs }, { -it.seq }))
        return ThreadIngest(
            title = Text.fix(title),
            participants = participants.map { Text.fix(it) },
            kept = kept,
            rawCount = rawCount,
        )
    }
}
