package com.igviewer.archive.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Port of the Rust core's `text::fix`.
 *
 * Instagram exports store text as UTF-8 bytes that were misdecoded as Latin-1, so
 * accented/emoji characters arrive mojibaked. The repair is deliberately
 * conservative: only applied when every char fits in a byte, and reverted on any
 * decoding failure. Values entering the dedupe key are NEVER fixed (see [Ingest]).
 */
object Text {
    fun fix(s: String): String {
        if (s.isEmpty()) return s
        val bytes = ByteArray(s.length)
        for (i in s.indices) {
            val c = s[i].code
            if (c > 0xFF) return s
            bytes[i] = c.toByte()
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            s
        }
    }
}
