package com.igviewer.archive.data

import com.igviewer.archive.ui.screens.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Media containment + search, exercised against the generated fixture. */
class MediaSearchTest {

    private fun repoRoot(): File {
        var d: File = File(".").absoluteFile
        while (true) {
            if (File(d, "fake-data/expected.json").isFile) return d
            d = d.parentFile ?: error("fake-data not found")
        }
    }

    private fun index(): ArchiveIndex = ArchiveIndex.index(File(repoRoot(), "fake-data/export"))

    @Test
    fun resolves_export_relative_media_uri() {
        val idx = index()
        val uri = "your_instagram_activity/messages/inbox/mira_okonkwo_4821/photos/photo_0000.png"
        assertEquals("image/png", MediaResolver.mimeFor(uri))
        val f = idx.mediaFile(uri)
        assertTrue("photo should resolve", f != null && f.isFile)
    }

    @Test
    fun basename_fallback_finds_moved_media() {
        val idx = index()
        val f = idx.mediaFile("some/bogus/prefix/photo_0000.png")
        assertTrue("basename fallback should find the file", f != null && f.name == "photo_0000.png")
    }

    @Test
    fun rejects_traversal_and_script_types() {
        val idx = index()
        assertNull("traversal must not resolve", idx.mediaFile("../../../etc/passwd"))
        assertNull("html is not renderable", MediaResolver.mimeFor("photos/evil_55.html"))
        assertNull("svg is not renderable", MediaResolver.mimeFor("photos/script_55.svg"))
        assertNull("unknown type", MediaResolver.mimeFor("files/a.pdf"))
    }

    @Test
    fun messages_carry_renderable_image_paths() {
        val idx = index()
        val items = UiMapping.messageItems(idx, "mira_okonkwo_4821")
        val withImages = items.count { it.imageFiles.isNotEmpty() }
        assertTrue("some messages should have renderable images", withImages > 0)
        for (it in items) {
            for (p in it.imageFiles) {
                assertTrue("image path must exist: $p", File(p).isFile)
            }
        }
    }

    @Test
    fun search_finds_matches_across_threads() {
        val idx = index()
        val hits: List<SearchResult> = UiMapping.search(idx, "ack")
        assertTrue("expected some 'ack' hits", hits.isNotEmpty())
        assertTrue(hits.all { it.snippet.contains("ack", ignoreCase = true) })
        assertEquals("queries under 2 chars return nothing", 0, UiMapping.search(idx, "a").size)
    }
}
