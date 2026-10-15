package com.igviewer.archive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoteStore's serializer is hand-rolled, so it is the one piece here with real parsing logic:
 * escaping, nesting and corrupt input all have to behave.
 */
class NoteStoreTest {

    private fun note(i: Int, text: String) = Note("arch", "thread$i", i, text, 1_000L + i)

    @Test
    fun roundTripsNotes() {
        val notes = listOf(note(1, "hello"), note(2, "world"))
        assertEquals(notes, NoteStore.decode(NoteStore.encode(notes)))
    }

    @Test
    fun roundTripsQuotesBackslashesAndNewlines() {
        val nasty = "he said \"hi\"\\ then\nnew\tline"
        val decoded = NoteStore.decode(NoteStore.encode(listOf(note(7, nasty))))
        assertEquals(nasty, decoded.single().text)
    }

    @Test
    fun roundTripsUnicode() {
        val text = "café \u2014 日本語 \uD83D\uDE00"
        val decoded = NoteStore.decode(NoteStore.encode(listOf(note(3, text))))
        assertEquals(text, decoded.single().text)
    }

    @Test
    fun emptyInputDecodesToNoNotes() {
        assertTrue(NoteStore.decode("").isEmpty())
        assertTrue(NoteStore.decode("   ").isEmpty())
        assertTrue(NoteStore.decode("[]").isEmpty())
    }

    @Test
    fun corruptJsonDegradesToEmptyRatherThanThrowing() {
        // A truncated write must never crash the screen that lists notes.
        assertTrue(NoteStore.decode("{\"a\":\"x\"").isEmpty())
        assertTrue(NoteStore.decode("[{\"a\":1}]").isEmpty())
        assertTrue(NoteStore.decode("not json at all").isEmpty())
    }

    @Test
    fun entriesMissingRequiredFieldsAreSkipped() {
        val json = """[{"a":"arch","t":"thread","i":4,"n":"kept","u":9},{"a":"arch","t":"thread","n":"no index"}]"""
        val decoded = NoteStore.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("kept", decoded.single().text)
        assertEquals(4, decoded.single().msgIndex)
    }

    @Test
    fun bracesInsideTextDoNotConfuseTheParser() {
        val text = "look at this } { } \"quoted\""
        val decoded = NoteStore.decode(NoteStore.encode(listOf(note(1, text))))
        assertEquals(text, decoded.single().text)
    }

    @Test
    fun idIdentifiesTheMessageItIsPinnedTo() {
        val a = Note("a", "b", 2, "x", 1)
        assertEquals(Note.idOf("a", "b", 2), a.id)
        // Same triple, different text or timestamp, is still the same note.
        assertEquals(a.id, a.copy(text = "y", updatedAt = 99).id)
        // Changing any component of the triple is a different note.
        assertTrue(a.id != a.copy(msgIndex = 3).id)
        assertTrue(a.id != a.copy(threadKey = "c").id)
        assertTrue(a.id != a.copy(archiveKey = "d").id)
    }

    @Test
    fun searchIsCaseInsensitiveAndNewestFirst() {
        val notes = listOf(
            note(1, "Buy MILK"),
            note(2, "milk shake"),
            note(3, "bread"),
        )
        assertEquals(listOf(2, 1), NoteStore.search(notes, "milk").map { it.msgIndex })
        // An empty query is "everything", still newest first.
        assertEquals(listOf(3, 2, 1), NoteStore.search(notes, "").map { it.msgIndex })
        assertTrue(NoteStore.search(notes, "zzz").isEmpty())
    }

    @Test
    fun notesFromDifferentArchivesAreDistinguished() {
        val a = Note("arch1", "t", 0, "same", 1)
        val b = Note("arch2", "t", 0, "same", 1)
        assertTrue(a.id != b.id)
        assertEquals(listOf(a, b), NoteStore.decode(NoteStore.encode(listOf(a, b))))
    }
}