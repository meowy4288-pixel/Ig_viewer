package com.igviewer.archive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Multi-import merges several exports into one archive. The dangerous part is two exports
 * carrying the same thread key, so that is what these pin down.
 */
class MultiArchiveIndexTest {

    private fun tmp(): File = File(System.getProperty("java.io.tmpdir"), "multi-archive-test").apply {
        deleteRecursively()
        mkdirs()
    }

    /** Writes a minimal but real export: inbox/<thread>/message_1.json. */
    private fun makeExport(
        parent: File,
        exportName: String,
        thread: String,
        sender: String,
        text: String,
    ): File {
        val root = File(parent, exportName).apply { mkdirs() }
        val inbox = File(root, "your_instagram_activity/messages/inbox/$thread").apply { mkdirs() }
        File(inbox, "message_1.json").writeText(
            """
            {
              "participants": [{"name": "$sender"}],
              "messages": [
                {"sender_name": "$sender", "timestamp_ms": 1700000000000, "content": "$text"}
              ]
            }
            """.trimIndent()
        )
        return root
    }

    @Test
    fun singleRootKeepsTheOriginalThreadKeys() {
        // Namespacing must not fire for a normal one-archive import, because per-chat
        // prefs (library tab, filter, notes) are keyed on these exact strings.
        val dir = tmp()
        val root = makeExport(dir, "one", "id_uk", "me", "hi")
        val idx = ArchiveIndex.indexAll(listOf(root))
        assertEquals(listOf("id_uk"), idx.threads.map { it.key })
    }

    @Test
    fun collidingThreadKeysAreNamespacedSoNeitherIsLost() {
        val dir = tmp()
        val a = makeExport(dir, "export-a", "id_uk", "me", "from a")
        val b = makeExport(dir, "export-b", "id_uk", "me", "from b")
        val idx = ArchiveIndex.indexAll(listOf(a, b))

        assertEquals(2, idx.threads.size)
        val keys = idx.threads.map { it.key }
        assertEquals("keys must be unique after merging", keys.size, keys.toSet().size)
        assertTrue("expected both to be namespaced: $keys", keys.none { it == "id_uk" })
        // Both still carry their own text, so the merge did not overwrite one with the other.
        val texts = idx.threads.map { it.lastPreview }.toSet()
        assertEquals(setOf("from a", "from b"), texts)
    }

    @Test
    fun nonCollidingThreadsFromDifferentExportsStayReadable() {
        val dir = tmp()
        val a = makeExport(dir, "export-a", "id_uk", "me", "a")
        val b = makeExport(dir, "export-b", "group_x", "me", "b")
        val idx = ArchiveIndex.indexAll(listOf(a, b))
        assertEquals(setOf("id_uk", "group_x"), idx.threads.map { it.key }.toSet())
    }

    @Test
    fun eachThreadResolvesMediaFromItsOwnExport() {
        // Both exports contain a media file with the SAME relative path. Thread A must not
        // be handed thread B's file, which is exactly why rootByKey exists.
        val dir = tmp()
        val a = makeExport(dir, "export-a", "dup", "me", "a")
        val b = makeExport(dir, "export-b", "dup", "me", "b")
        val relA = "your_instagram_activity/messages/inbox/dup/photos/a.jpg"
        val relB = "your_instagram_activity/messages/inbox/dup/photos/a.jpg"
        File(a, relA).apply { parentFile.mkdirs(); writeText("AAA") }
        File(b, relB).apply { parentFile.mkdirs(); writeText("BBB") }

        val idx = ArchiveIndex.indexAll(listOf(a, b))
        val keyA = idx.threads.first { it.lastPreview == "a" }.key
        val keyB = idx.threads.first { it.lastPreview == "b" }.key
        assertNotEquals(keyA, keyB)

        assertEquals("AAA", idx.mediaFile(relA, keyA)?.readText())
        assertEquals("BBB", idx.mediaFile(relB, keyB)?.readText())
    }

    @Test
    fun duplicateRootsAreCollapsed() {
        val dir = tmp()
        val a = makeExport(dir, "export-a", "id_uk", "me", "hi")
        val idx = ArchiveIndex.indexAll(listOf(a, a))
        assertEquals(1, idx.threads.size)
        assertEquals(1, idx.roots.size)
    }

    @Test
    fun emptyRootListIsRejectedRatherThanSilentlyEmpty() {
        var threw = false
        try {
            ArchiveIndex.indexAll(emptyList())
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("an empty import must fail loudly, not silently show zero chats", threw)
    }

    @Test
    fun snapshotSurvivesMultipleRoots() {
        val dir = tmp()
        val a = makeExport(dir, "export-a", "id_uk", "me", "a")
        val b = makeExport(dir, "export-b", "group_x", "me", "b")
        val idx = ArchiveIndex.indexAll(listOf(a, b))
        val snap = File(dir, "index.snapshot")
        idx.saveSnapshot(snap)

        val loaded = ArchiveIndex.loadSnapshot(snap)
        assertEquals(null, null)
        assertTrue(loaded != null && loaded.threads.isNotEmpty())
        assertEquals(2, loaded!!.roots.size)
        // The per-thread media mapping has to survive too, or media goes missing until
        // the next re-import. Point each thread at a real file and check we get that one.
        File(a, "your_instagram_activity/messages/inbox/id_uk/photos/x.jpg")
            .apply { parentFile.mkdirs(); writeText("AAA") }
        val key = loaded.threads.first { it.key == "id_uk" }.key
        assertEquals(
            "AAA",
            loaded.mediaFile("your_instagram_activity/messages/inbox/id_uk/photos/x.jpg", key)?.readText(),
        )
    }
}