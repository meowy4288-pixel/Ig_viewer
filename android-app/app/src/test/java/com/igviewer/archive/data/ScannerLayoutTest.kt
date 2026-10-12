package com.igviewer.archive.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Covers the export-layout shapes a real archive can arrive in, and the decode
 * fallbacks that keep a single odd file from dropping a whole thread. Fake data.
 */
class ScannerLayoutTest {

    private fun tempRoot(): File = Files.createTempDirectory("ig-layout").toFile()

    private fun msgJson(sender: String, ts: Long, content: String): String =
        """{"participants":[{"name":"me"}],"messages":[""" +
            """{"sender_name":"$sender","timestamp_ms":$ts,"content":"$content"}]}"""

    private fun writeMessage(dir: File, sender: String, ts: Long, content: String) {
        dir.mkdirs()
        File(dir, "message_1.json").writeText(msgJson(sender, ts, content), StandardCharsets.UTF_8)
    }

    @Test
    fun resolves_inbox_under_two_wrapper_folders() {
        val root = tempRoot()
        val inbox = File(
            root,
            "instagram-user-2026-01-01/your_instagram_activity/messages/inbox",
        )
        writeMessage(File(inbox, "alice"), "me", 1, "hi")
        val index = ArchiveIndex.index(root)
        assertEquals(1, index.threads.size)
        assertEquals("alice", index.threads[0].key)
    }

    @Test
    fun resolves_inbox_under_one_wrapper_folder() {
        val root = tempRoot()
        val inbox = File(root, "export-copy/your_instagram_activity/messages/inbox")
        writeMessage(File(inbox, "bob"), "me", 1, "hi")
        assertEquals("bob", ArchiveIndex.index(root).threads.single().key)
    }

    @Test
    fun resolves_when_root_is_the_export_root() {
        val root = tempRoot()
        writeMessage(
            File(root, "your_instagram_activity/messages/inbox/carol"),
            "me", 1, "hi",
        )
        assertEquals("carol", ArchiveIndex.index(root).threads.single().key)
    }

    @Test
    fun resolves_when_root_is_the_instagram_folder() {
        val root = tempRoot()
        writeMessage(File(root, "messages/inbox/dave"), "me", 1, "hi")
        assertEquals("dave", ArchiveIndex.index(root).threads.single().key)
    }

    @Test
    fun resolves_legacy_messages_root() {
        val root = tempRoot()
        writeMessage(File(root, "messages/inbox/erin"), "me", 1, "hi")
        assertEquals("erin", ArchiveIndex.index(root).threads.single().key)
    }

    @Test
    fun bom_and_non_utf8_file_is_repaired_not_dropped() {
        val root = tempRoot()
        val dir = File(root, "your_instagram_activity/messages/inbox/frank")
        dir.mkdirs()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        // "café" written as ISO-8859-1: byte 0xE9 is invalid UTF-8, so the
        // strict decoder must fail over to Latin-1 rather than skip the file.
        val latin1 = msgJson("me", 1, "caf\u00e9").toByteArray(StandardCharsets.ISO_8859_1)
        File(dir, "message_1.json").writeBytes(bom + latin1)

        val index = ArchiveIndex.index(root)
        assertEquals(0, index.parseFailures)
        assertEquals("frank", index.threads.single().key)
        assertEquals("caf\u00e9", index.messages("frank").single().content)
    }

    @Test
    fun oversized_message_file_is_skipped_not_crashed() {
        val root = tempRoot()
        val dir = File(root, "your_instagram_activity/messages/inbox/grace")
        dir.mkdirs()
        val big = File(dir, "message_1.json")
        big.outputStream().use { o ->
            o.write(ByteArray(Scanner.MAX_JSON_BYTES.toInt() + 1) { 'x'.code.toByte() })
        }
        val index = ArchiveIndex.index(root)
        assertEquals(0, index.threads.size)
        assertEquals(1, index.skippedFiles)
    }
}
