package com.igviewer.archive.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the Kotlin ingest against the same ground truth as the Rust core
 * (`fake-data/expected.json`). Fake data only.
 */
class ArchiveIndexTest {

    private fun repoRoot(): File {
        var d: File = File(".").absoluteFile
        while (true) {
            if (File(d, "fake-data/expected.json").isFile) return d
            d = d.parentFile ?: error(
                "fake-data/expected.json not found above ${File(".").absolutePath}"
            )
        }
    }

    @Test
    fun matches_expected_counts_titles_and_order() {
        val root = File(repoRoot(), "fake-data/export")
        val expected = JSONObject(File(repoRoot(), "fake-data/expected.json").readText())
        val index = ArchiveIndex.index(root)

        assertEquals("no file should fail to parse", 0, index.parseFailures)

        val byKey = index.threads.associateBy { it.key }
        val expectedThreads = expected.getJSONArray("threads")
        var checked = 0

        for (i in 0 until expectedThreads.length()) {
            val exp = expectedThreads.getJSONObject(i)
            val key = exp.getString("key")
            val msgCount = exp.getInt("message_count")
            if (msgCount == 0) {
                assertTrue("$key should be absent (no files)", byKey[key] == null)
                continue
            }
            val actual = byKey[key] ?: error("thread $key missing from index")
            assertEquals("$key title", exp.getString("title"), actual.title)
            assertEquals("$key kept count", msgCount, actual.keptCount)
            assertEquals("$key raw count", exp.getInt("raw_count"), actual.rawCount)

            val expectedOrder = exp.getJSONArray("order")
            val msgs = index.messages(key)
            assertEquals("$key display length", expectedOrder.length(), msgs.size)
            for (j in 0 until expectedOrder.length()) {
                val e = expectedOrder.getJSONObject(j)
                val ord = e.getJSONObject("ord")
                val m = msgs[j]
                val expContent = if (e.has("content") && !e.isNull("content")) e.getString("content") else ""
                val where = "$key[$j] id=${e.getString("id")}"
                assertEquals("$where ts_ms", e.getLong("ts_ms"), m.tsMs)
                assertEquals("$where file", ord.getLong("file"), m.fileNo)
                assertEquals("$where index", ord.getInt("index").toLong(), m.idxInFile.toLong())
                assertEquals("$where sender", e.getString("sender"), m.sender)
                assertEquals("$where content", expContent, m.content ?: "")
            }
            checked++
        }
        assertEquals("expected 4 non-empty threads", 4, checked)
    }

    @Test
    fun cross_file_duplicate_keeps_lower_file() {
        val root = File(repoRoot(), "fake-data/export")
        val expected = JSONObject(File(repoRoot(), "fake-data/expected.json").readText())
        val index = ArchiveIndex.index(root)
        val exp = expected.getJSONArray("threads")
            .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            .first { it.getString("key") == "mira_okonkwo_4821" }

        val msgs = index.messages("mira_okonkwo_4821")
        for (d in exp.getJSONObject("assertions").getJSONArray("dropped").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }) {
            val (keptFile, keptIdx) = parseLoc(d.getString("kept"))
            val (dropFile, dropIdx) = parseLoc(d.getString("dropped"))
            assertTrue(
                "winner ${d.getString("kept")} must exist",
                msgs.any { it.fileNo == keptFile && it.idxInFile == keptIdx },
            )
            assertTrue(
                "dropped ${d.getString("dropped")} must be absent",
                msgs.none { it.fileNo == dropFile && it.idxInFile == dropIdx },
            )
        }
    }

    private fun parseLoc(s: String): Pair<Long, Int> {
        // "file4:idx6"
        val f = s.substringAfter("file").substringBefore(":")
        val i = s.substringAfter("idx")
        return f.toLong() to i.toInt()
    }
}
