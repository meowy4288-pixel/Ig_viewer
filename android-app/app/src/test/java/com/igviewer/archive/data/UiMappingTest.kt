package com.igviewer.archive.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the voice-note MIME gate. Instagram exports voice notes as AAC audio inside an
 * `.mp4` container, so [MediaResolver] reports `video/mp4`; if that is rejected the notes
 * render as a dead `[Voice note]` chip instead of playing.
 */
class UiMappingTest {

    @Test
    fun accepts_real_audio_mimes() {
        assertTrue(UiMapping.isAudioContainer("audio/mpeg"))
        assertTrue(UiMapping.isAudioContainer("audio/wav"))
        assertTrue(UiMapping.isAudioContainer("audio/aac"))
    }

    @Test
    fun accepts_audio_in_a_video_container() {
        assertTrue(UiMapping.isAudioContainer("video/mp4"))
        assertTrue(UiMapping.isAudioContainer("video/quicktime"))
    }

    @Test
    fun rejects_video_themes_and_unknowns() {
        assertFalse(UiMapping.isAudioContainer(null))
        assertFalse(UiMapping.isAudioContainer("video/webm"))
        assertFalse(UiMapping.isAudioContainer("image/png"))
        assertFalse(UiMapping.isAudioContainer("text/html"))
    }
}
