package com.igviewer.archive.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * A named color palette. Values are lifted from DESIGN.html. To add a theme, add a
 * new [ThemeVariant] below and it appears in the Settings customization picker.
 */
data class Palette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val accentDim: Color,
    val gilt: Color,
    val text: Color,
    val muted: Color,
)

/** The default dark palette ("Midnight"). */
val MidnightPalette = Palette(
    isDark = true,
    bg = Color(0xFF000000),
    surface = Color(0xFF141414),
    line = Color(0xFF2B2B2B),
    accent = Color(0xFFECECEC),
    onAccent = Color(0xFF000000),
    accentDim = Color(0xFF2E2E2E),
    gilt = Color(0xFFC4C4C4),
    text = Color(0xFFF2F2F2),
    muted = Color(0xFF8A8A8A),
)

/** The light opposite ("Porcelain"). */
val PorcelainPalette = Palette(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF1F1F1),
    line = Color(0xFFD9D9D9),
    accent = Color(0xFF000000),
    onAccent = Color(0xFFFFFFFF),
    accentDim = Color(0xFFE2E2E2),
    gilt = Color(0xFF444444),
    text = Color(0xFF000000),
    muted = Color(0xFF666666),
)

/** A selectable theme. `id` is persisted; add more variants here later. */
enum class ThemeVariant(val id: String, val label: String, val palette: Palette) {
    MIDNIGHT("midnight", "Midnight", MidnightPalette),
    PORCELAIN("porcelain", "Porcelain", PorcelainPalette),
    ;

    companion object {
        fun byId(id: String?): ThemeVariant = entries.firstOrNull { it.id == id } ?: MIDNIGHT
    }
}

/**
 * The active design tokens. Backed by snapshot state so switching a theme recomposes
 * every screen with no call-site changes. [apply] is called once per theme change by
 * [IGArchiveTheme]; re-applying the same variant is a no-op (structural equality).
 */
object Tokens {
    var Bg by mutableStateOf(MidnightPalette.bg); private set
    var Surface by mutableStateOf(MidnightPalette.surface); private set
    var Line by mutableStateOf(MidnightPalette.line); private set
    var Accent by mutableStateOf(MidnightPalette.accent); private set
    var OnAccent by mutableStateOf(MidnightPalette.onAccent); private set
    var AccentDim by mutableStateOf(MidnightPalette.accentDim); private set
    var Gilt by mutableStateOf(MidnightPalette.gilt); private set
    var Text by mutableStateOf(MidnightPalette.text); private set
    var Muted by mutableStateOf(MidnightPalette.muted); private set

    val Serif = FontFamily.Serif

    fun apply(variant: ThemeVariant) {
        val p = variant.palette
        Bg = p.bg
        Surface = p.surface
        Line = p.line
        Accent = p.accent
        OnAccent = p.onAccent
        AccentDim = p.accentDim
        Gilt = p.gilt
        Text = p.text
        Muted = p.muted
    }
}
