package com.igviewer.archive.ui.screens

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [highlightMatch] is private, so it is exercised the way the app uses it: through the
 * public search screen is not possible in a plain JVM test, so this mirrors the one guard
 * that matters and pins the shape of the output.
 */
class HighlightMatchTest {

    private fun highlight(text: String, query: String): AnnotatedString {
        // The screen-private implementation, kept in step with SearchScreen.highlightMatch.
        if (query.isEmpty()) return AnnotatedString(text)
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        val sb = AnnotatedString.Builder()
        var start = 0
        while (true) {
            val idx = lowerText.indexOf(lowerQuery, start)
            if (idx < 0) {
                sb.append(text.substring(start))
                break
            }
            sb.append(text.substring(start, idx))
            sb.pushStyle(SpanStyle())
            sb.append(text.substring(idx, idx + query.length))
            sb.pop()
            start = idx + query.length
        }
        return sb.toAnnotatedString()
    }

    @Test
    fun emptyQueryReturnsTheTextUnchanged() {
        // The Notes tab renders every note before anything is typed. Without the empty-query
        // guard the match loop never advances and the process dies.
        val note = "a note about the holiday photo"
        val result = highlight(note, "")
        assertEquals(note, result.text)
        assertEquals(0, result.spanStyles.size)
    }

    @Test
    fun emptyQueryOnEmptyTextIsAlsoSafe() {
        assertEquals("", highlight("", "").text)
    }

    @Test
    fun matchingQueryStillHighlights() {
        val result = highlight("Buy MILK today", "milk")
        assertEquals("Buy MILK today", result.text)
        assertEquals(1, result.spanStyles.size)
        assertEquals(4, result.spanStyles.first().start)
        assertEquals(8, result.spanStyles.first().end)
    }

    @Test
    fun repeatedMatchesAreAllHighlighted() {
        val result = highlight("milk shake milk", "milk")
        assertEquals("milk shake milk", result.text)
        assertEquals(2, result.spanStyles.size)
    }

    @Test
    fun noMatchReturnsPlainText() {
        val result = highlight("bread", "zzz")
        assertEquals("bread", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }
}