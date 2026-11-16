package com.igviewer.archive.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The full-screen viewer's paging model.
 *
 * These are pure list operations, so the order a user swipes through is pinned here
 * instead of only being observable by opening the app.
 */
class PhotoPagerTest {

    private fun message(
        index: Int,
        images: List<String> = emptyList(),
        videos: List<String> = emptyList(),
        audio: List<String> = emptyList(),
    ) = MessageItem(
        sender = "ana",
        text = "",
        time = "09:41",
        isMe = false,
        imageFiles = images,
        audioFiles = audio,
        videoFiles = videos,
        msgIndex = index,
    )

    private fun photo(path: String, msgIndex: Int) = OverlayTarget(path, msgIndex)

    @Test
    fun chatPhotosFollowMessageOrderAndCarryTheirMessage() {
        val messages = listOf(
            message(0, images = listOf("a.png", "b.png")),
            message(1, images = listOf("c.png")),
        )
        assertEquals(
            listOf(photo("a.png", 0), photo("b.png", 0), photo("c.png", 1)),
            chatPhotoTargets(messages),
        )
    }

    @Test
    fun chatPhotosSkipVideosAndVoiceNotes() {
        val messages = listOf(
            message(0, images = listOf("a.png"), videos = listOf("v.mp4"), audio = listOf("n.wav")),
            message(1, videos = listOf("w.mp4")),
        )
        assertEquals(listOf(photo("a.png", 0)), chatPhotoTargets(messages))
    }

    @Test
    fun chatPhotosOfAnEmptyChatAreEmpty() {
        assertEquals(emptyList<OverlayTarget>(), chatPhotoTargets(emptyList()))
    }

    @Test
    fun pagerOpensOnTheTappedPhoto() {
        val items = listOf(photo("a.png", 0), photo("b.png", 1), photo("c.png", 2))
        val pager = photoPagerOf(items, "b.png")
        assertEquals(3, pager.items.size)
        assertEquals(1, pager.index)
        assertEquals("b.png", pager.items[pager.index].path)
    }

    @Test
    fun pagerKeepsTheWholeListSoSwipingCanGoBothWays() {
        val items = listOf(photo("a.png", 0), photo("b.png", 1), photo("c.png", 2))
        val pager = photoPagerOf(items, "b.png")
        assertEquals("a.png", pager.items[pager.index - 1].path)
        assertEquals("c.png", pager.items[pager.index + 1].path)
    }

    @Test
    fun pagerOfOnePhotoHasNowhereToGo() {
        val pager = photoPagerOf(listOf(photo("a.png", 0)), "a.png")
        assertEquals(1, pager.items.size)
        assertEquals(0, pager.index)
    }

    @Test
    fun unknownPhotoOpensAsItselfInsteadOfSomeoneElses() {
        // A path the chat no longer lists must not resolve to items[0].
        val pager = photoPagerOf(listOf(photo("a.png", 0), photo("b.png", 1)), "gone.png")
        assertEquals(1, pager.items.size)
        assertEquals(0, pager.index)
        assertEquals("gone.png", pager.items[0].path)
        assertEquals(null, pager.items[0].msgIndex)
    }

    @Test
    fun galleryPagerKeepsGridOrderAndSkipsVideos() {
        val entries = listOf(
            GalleryEntry("a.png", isVideo = false, msgIndex = 4),
            GalleryEntry("v.mp4", isVideo = true, msgIndex = 5),
            GalleryEntry("b.png", isVideo = false, msgIndex = 6),
        )
        val pager = galleryPhotoPager(entries, "b.png")
        assertEquals(listOf("a.png", "b.png"), pager.items.map { it.path })
        assertEquals(1, pager.index)
        assertEquals(6, pager.items[1].msgIndex ?: -1)
    }

    @Test
    fun aGalleryOfOnlyVideosFallsBackToTheTappedFile() {
        val entries = listOf(GalleryEntry("v.mp4", isVideo = true, msgIndex = 2))
        val pager = galleryPhotoPager(entries, "v.mp4")
        assertEquals(1, pager.items.size)
        assertEquals("v.mp4", pager.items[0].path)
    }
}
