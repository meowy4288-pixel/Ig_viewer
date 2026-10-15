package com.igviewer.archive.data

import com.igviewer.archive.ui.screens.MessageItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The chat is now mapped in slices so it becomes usable before the whole conversation is
 * built. The risk is that slicing changes the output, so these pin slice-by-slice mapping
 * to be identical to mapping everything at once -- especially day separators, which carry
 * state across chunk boundaries.
 */
class ChunkedMappingTest {

    private fun indexWith(days: Int, perDay: Int): Pair<ArchiveIndex, Int> {
        val root = File(System.getProperty("java.io.tmpdir"), "chunked-$days-$perDay").apply {
            deleteRecursively(); mkdirs()
        }
        val dir = File(root, "your_instagram_activity/messages/inbox/alice").apply { mkdirs() }
        val sb = StringBuilder()
        sb.append("{\"participants\":[{\"name\":\"me\"},{\"name\":\"alice\"}],\"messages\":[")
        var n = 0
        for (d in 0 until days) {
            val base = 1_700_000_000_000L + d * 86_400_000L
            for (i in 0 until perDay) {
                if (n > 0) sb.append(',')
                val who = if (i % 2 == 0) "me" else "alice"
                val ts = base + i * 60_000L
                sb.append("{\"sender_name\":\"").append(who)
                sb.append("\",\"timestamp_ms\":").append(ts)
                sb.append(",\"content\":\"m").append(n).append("\"}")
                n++
            }
        }
        sb.append("]}")
        File(dir, "message_1.json").writeText(sb.toString())
        return ArchiveIndex.indexAll(listOf(root)) to n
    }

    @Test
    fun slicingInDifferentSizesProducesIdenticalItems() {
        val (idx, n) = indexWith(days=6, perDay=40)   // 240 messages across 6 day boundaries
        val whole = UiMapping.messageItems(idx, "alice")

        for (chunk in listOf(1, 7, 50, 239, 240, 500)) {
            val acc = ArrayList<MessageItem>()
            var day = ""
            var from = 0
            while (from < n) {
                val (items, after) = UiMapping.messageChunk(idx, "alice", idx.messages("alice"), from, chunk, fromDay = day)
                acc.addAll(items)
                day = after
                from += chunk
            }
            assertEquals("chunk size $chunk changed the result", whole, acc)
        }
    }

    @Test
    fun daySeparatorsSurviveChunkBoundaries() {
        // A day boundary landing exactly on a chunk edge is the case most likely to drop a
        // separator or duplicate one.
        val (idx, n) = indexWith(days=4, perDay=25)   // 100 messages, 4 days
        val whole = UiMapping.messageItems(idx, "alice")
        val expectedSeparators = whole.count { it.daySeparator != null }
        assertEquals(4, expectedSeparators)

        for (chunk in listOf(25, 50, 26, 7)) {
            val acc = ArrayList<MessageItem>()
            var day = ""
            var from = 0
            while (from < n) {
                val (items, after) = UiMapping.messageChunk(idx, "alice", idx.messages("alice"), from, chunk, fromDay = day)
                acc.addAll(items)
                day = after
                from += chunk
            }
            assertEquals("chunk $chunk separators", expectedSeparators, acc.count { it.daySeparator != null })
            assertEquals(whole, acc)
        }
    }

    @Test
    fun msgIndexStaysAbsoluteNotRelativeToTheSlice() {
        // Notes, "jump to message" and per-chat avatars are all keyed on this index, so a
        // slice must not renumber from zero.
        val (idx, _) = indexWith(days=3, perDay=20)
        val msgs = idx.messages("alice")
        val (second, _) = UiMapping.messageChunk(idx, "alice", msgs, from = 20, count = 10)
        assertEquals(20, second.first().msgIndex)
        assertEquals(29, second.last().msgIndex)
    }

    @Test
    fun sliceRunningPastTheEndIsClampedNotCrashed() {
        val (idx, n) = indexWith(days=2, perDay=10)
        val (items, _) = UiMapping.messageChunk(idx, "alice", idx.messages("alice"), from = n - 3, count = 999)
        assertEquals(3, items.size)
    }
}
