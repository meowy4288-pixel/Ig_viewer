package com.igviewer.archive.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Design tokens lifted verbatim from DESIGN.html. The dark palette is the primary
 * target; light values are kept in parity so a future theme toggle stays faithful.
 */
object Tokens {
    // Dark (default)
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val Line = Color(0xFF2B2B2B)
    val Accent = Color(0xFFECECEC)
    val OnAccent = Color(0xFF000000)
    val AccentDim = Color(0xFF2E2E2E)
    val Gilt = Color(0xFFC4C4C4)
    val Text = Color(0xFFF2F2F2)
    val Muted = Color(0xFF8A8A8A)

    val Serif = FontFamily.Serif
}
