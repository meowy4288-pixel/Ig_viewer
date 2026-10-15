package com.igviewer.archive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cheap, bounded basename lookup that replaced a whole-tree media walk.
 *
 * These pin the safety rules too, since this walks directories the export controls.
 */
class ThreadMediaLookupTest {

    private fun export(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "basename-lookup").apply {
            deleteRecursively(); mkdirs()
        }
        val dir = File(root, "your_instagram_activity/messages/inbox/alice/photos").apply { mkdirs() }
        File(dir, "384312553936495").writeText("bare")          // extensionless
        File(dir, "955475004104381.jpg").writeText("jpg")        // with extension
        File(root, "your_instagram_activity/messages/inbox/alice/videos").apply { mkdirs() }
            .let { File(it, "clip.mp4").writeText("mp4") }
        // A different thread, to prove we do not cross-match.
        File(root, "your_instagram_activity/messages/inbox/bob/photos").apply { mkdirs() }
            .let { File(it, "bobonly.jpg").writeText("bob") }
        return root
    }

    @Test
    fun findsExtensionlessMediaWhenTheUriClaimsAnExtension() {
        val root = export()
        assertEquals(
            "bare",
            MediaResolver.resolveByBasename(root, "alice", "384312553936495.jpg")?.readText(),
        )
    }

    @Test
    fun findsMediaByItsExactName() {
        val root = export()
        assertEquals(
            "jpg",
            MediaResolver.resolveByBasename(root, "alice", "955475004104381.jpg")?.readText(),
        )
    }

    @Test
    fun looksInTheOtherMediaSubfoldersToo() {
        val root = export()
        assertEquals("mp4", MediaResolver.resolveByBasename(root, "alice", "clip.mp4")?.readText())
    }

    @Test
    fun neverLooksInsideAnotherThreadsFolder() {
        val root = export()
        assertNull(MediaResolver.resolveByBasename(root, "alice", "bobonly.jpg"))
    }

    @Test
    fun rejectsNamesThatTryToEscape() {
        val root = export()
        // A name with separators must never be treated as a bare filename.
        assertNull(MediaResolver.resolveByBasename(root, "alice", "../bob/photos/bobonly.jpg"))
        assertNull(MediaResolver.resolveByBasename(root, "alice", "a/b"))
        assertNull(MediaResolver.resolveByBasename(root, "alice", ".."))
        assertNull(MediaResolver.resolveByBasename(root, "alice", ""))
    }

    @Test
    fun worksWithANamespacedThreadKey() {
        val root = export()
        // After merging two archives, keys can look like "<archive>\u00b7alice".
        assertEquals(
            "jpg",
            MediaResolver.resolveByBasename(root, "export-a\u00b7alice", "955475004104381.jpg")?.readText(),
        )
    }

    @Test
    fun endToEndResolutionFindsExtensionlessMediaWithoutTheWholeTreeIndex() {
        val root = File(System.getProperty("java.io.tmpdir"), "basename-e2e").apply {
            deleteRecursively(); mkdirs()
        }
        val dir = File(root, "your_instagram_activity/messages/inbox/alice/photos").apply { mkdirs() }
        File(dir, "384312553936495").writeText("bare")
        val inbox = File(root, "your_instagram_activity/messages/inbox/alice")
        File(inbox, "message_1.json").writeText(
            """{"participants":[{"name":"me"},{"name":"alice"}],"messages":[
              {"sender_name":"me","timestamp_ms":1700000000000,
               "content":"hi","photos":[{"uri":"your_instagram_activity/messages/inbox/alice/photos/384312553936495.jpg"}]}]}""",
        )
        val idx = ArchiveIndex.indexAll(listOf(root))
        val items = UiMapping.messageItems(idx, "alice")
        assertTrue("expected the image to resolve, got ${items.single().imageFiles}", items.single().imageFiles.isNotEmpty())
    }
}
