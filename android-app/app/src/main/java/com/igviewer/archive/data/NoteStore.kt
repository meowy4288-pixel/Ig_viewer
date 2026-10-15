package com.igviewer.archive.data

/**
 * A user-written note pinned to one message.
 *
 * Identity is (archive, thread, message index) — the same triple [UiMapping.search] already
 * reports, so a note can always be jumped back to. Notes hold nothing but what the user typed:
 * no message text, no file paths.
 */
data class Note(
    val archiveKey: String,
    val threadKey: String,
    val msgIndex: Int,
    val text: String,
    val updatedAt: Long,
) {
    val id: String get() = idOf(archiveKey, threadKey, msgIndex)

    companion object {
        fun idOf(archiveKey: String, threadKey: String, msgIndex: Int): String =
            "$archiveKey|$threadKey|$msgIndex"
    }
}

/**
 * Persistence for [Note]. Stored in prefs as a tiny JSON array.
 *
 * Deliberately hand-rolled instead of using `org.json`: that class is a throwing stub in
 * local JVM unit tests, so the encode/decode round-trip would be untestable. This is plain
 * Kotlin with no Android dependency, so [encode]/[decode] are unit-tested directly.
 */
object NoteStore {

    fun all(prefs: Prefs): List<Note> = decode(prefs.notesJson)

    fun forMessage(prefs: Prefs, archiveKey: String, threadKey: String, msgIndex: Int): Note? =
        all(prefs).firstOrNull { it.id == Note.idOf(archiveKey, threadKey, msgIndex) }

    /** Insert or replace the note for this message. Blank text deletes the note instead. */
    fun save(prefs: Prefs, note: Note) {
        val trimmed = note.text.trim()
        val existing = all(prefs).filterNot { it.id == note.id }
        if (trimmed.isEmpty()) {
            prefs.notesJson = encode(existing)
        } else {
            prefs.notesJson = encode(existing + note.copy(text = trimmed))
        }
    }

    fun delete(prefs: Prefs, archiveKey: String, threadKey: String, msgIndex: Int) {
        val id = Note.idOf(archiveKey, threadKey, msgIndex)
        prefs.notesJson = encode(all(prefs).filterNot { it.id == id })
    }

    /** Drop every note belonging to an archive that is no longer imported. */
    fun forgetArchive(prefs: Prefs, archiveKey: String) {
        prefs.notesJson = encode(all(prefs).filterNot { it.archiveKey == archiveKey })
    }

    /** Case-insensitive substring match, newest first. Empty query returns everything. */
    fun search(notes: List<Note>, query: String): List<Note> {
        val q = query.trim()
        val hit = if (q.isEmpty()) notes else notes.filter { it.text.contains(q, ignoreCase = true) }
        return hit.sortedByDescending { it.updatedAt }
    }

    fun encode(notes: List<Note>): String = buildString {
        append('[')
        notes.forEachIndexed { i, n ->
            if (i > 0) append(',')
            append('{')
            field("a", n.archiveKey); append(',')
            field("t", n.threadKey); append(',')
            number("i", n.msgIndex.toLong()); append(',')
            field("n", n.text); append(',')
            number("u", n.updatedAt)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.number(key: String, value: Long) {
        append('"').append(key).append("\":").append(value)
    }

    private fun StringBuilder.field(key: String, value: String) {
        append('"').append(key).append("\":\"")
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /**
     * Tolerant parser: returns an empty list for anything it cannot read, so a corrupted
     * prefs value degrades to "no notes" instead of crashing the screen that lists them.
     */
    fun decode(json: String): List<Note> {
        if (json.isBlank()) return emptyList()
        val out = ArrayList<Note>()
        val objects = json.objects()
        for (obj in objects) {
            val a = obj.string("a") ?: continue
            val t = obj.string("t") ?: continue
            val i = obj.string("i")?.toIntOrNull() ?: continue
            val n = obj.string("n") ?: continue
            val u = obj.string("u")?.toLongOrNull() ?: 0L
            out.add(Note(a, t, i, n, u))
        }
        return out
    }

    /** Extracts the balanced `{...}` spans of a JSON array, ignoring braces inside strings. */
    private fun String.objects(): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var escaped = false
        var i = 0
        while (i < length) {
            val c = this[i]
            when {
                escaped -> escaped = false
                inStr && c == '\\' -> escaped = true
                c == '"' -> inStr = !inStr
                inStr -> Unit
                c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(substring(start, i + 1))
                        start = -1
                    }
                    if (depth < 0) return out
                }
            }
            i++
        }
        return out
    }

    /** Reads one `"key":"value"` pair out of an object span; numbers are returned as strings. */
    private fun String.string(key: String): String? {
        var i = 0
        val buf = StringBuilder()
        while (i < length) {
            val q = indexOf('"', i)
            if (q < 0) return null
            buf.setLength(0)
            val afterKey = readStringFrom(q, buf)
            if (afterKey < 0) return null
            val name = buf.toString()
            val colon = skipWs(afterKey)
            if (colon >= length || this[colon] != ':') {
                i = afterKey
                continue
            }
            val vq = skipWs(colon + 1)
            if (vq >= length) return null
            buf.setLength(0)
            val afterValue = if (this[vq] == '"') {
                readStringFrom(vq, buf)
            } else {
                // Bare token (a number, or true/false/null): read up to the next separator.
                var k = vq
                while (k < length && this[k] != ',' && this[k] != '}') k++
                buf.append(this, vq, k)
                k
            }
            if (afterValue < 0) return null
            if (name == key) return buf.toString()
            i = afterValue
        }
        return null
    }

    /**
     * Reads the string literal that starts at [start], appending its decoded form to [out],
     * and returns the index just past the closing quote (or -1 if the literal is unterminated).
     */
    private fun String.readStringFrom(start: Int, out: StringBuilder): Int {
        var i = start + 1
        while (i < length) {
            when (val c = this[i]) {
                '\\' -> {
                    if (i + 1 >= length) return -1
                    when (val e = this[i + 1]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 5 >= length) return -1
                            out.append(this.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                    i++
                }
                '"' -> return i + 1
                else -> out.append(c)
            }
            i++
        }
        return -1
    }

    private fun String.skipWs(from: Int): Int {
        var i = from
        while (i < length && this[i].isWhitespace()) i++
        return i
    }
}