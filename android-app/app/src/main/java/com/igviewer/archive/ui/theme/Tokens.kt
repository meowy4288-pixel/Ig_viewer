package com.igviewer.archive.ui.theme

import androidx.compose.ui.unit.dp

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

// The reference set (Rouge Noir / Blue Coral / Cognac / White Sand / Anthracite).
// Each is a single fixed palette (its own `isDark`), not a dark/light pair. Rule
// from the reference: one hero colour owns bg/surface/accent, warm brass (cognac)
// is the gilt, and hero colours too dark to read as a bubble are lifted a few
// percent. Midnight/Porcelain stay first and remain the default.

/** Rouge Noir: wine-black room, wine bubbles, cognac-gold titles. */
val RougeNoirPalette = Palette(
    isDark = true,
    bg = Color(0xFF0F0608),
    surface = Color(0xFF1D0B10),
    line = Color(0xFF3B1A22),
    accent = Color(0xFF7A1424),
    onAccent = Color(0xFFF4ECE6),
    accentDim = Color(0xFF3A121A),
    gilt = Color(0xFFC98A54),
    text = Color(0xFFE7E3DD),
    muted = Color(0xFF9A8884),
)

/** Blue Coral: deep sea-teal, sand text, warm cognac accents as contrast. */
val BlueCoralPalette = Palette(
    isDark = true,
    bg = Color(0xFF051312),
    surface = Color(0xFF0C2423),
    line = Color(0xFF1F4443),
    accent = Color(0xFF0F5A58),
    onAccent = Color(0xFFE7E3DD),
    accentDim = Color(0xFF0E3332),
    gilt = Color(0xFFD08A55),
    text = Color(0xFFE7E3DD),
    muted = Color(0xFF86A09E),
)

/** Cognac: warm leather-brown dark, exact cognac bubbles, champagne titles. */
val CognacPalette = Palette(
    isDark = true,
    bg = Color(0xFF120C08),
    surface = Color(0xFF211610),
    line = Color(0xFF3D2A1E),
    accent = Color(0xFF8A4E2A),
    onAccent = Color(0xFFF6EDE4),
    accentDim = Color(0xFF3A2314),
    gilt = Color(0xFFD8C39A),
    text = Color(0xFFE7E3DD),
    muted = Color(0xFF9C8B7C),
)

/** White Sand: the light theme. Sand paper, anthracite ink, cognac titles. */
val WhiteSandPalette = Palette(
    isDark = false,
    bg = Color(0xFFE7E3DD),
    surface = Color(0xFFF5F2EE),
    line = Color(0xFFCFC9C0),
    accent = Color(0xFF1B1B1F),
    onAccent = Color(0xFFE7E3DD),
    accentDim = Color(0xFFD9CFC2),
    gilt = Color(0xFF8A4E2A),
    text = Color(0xFF1B1B1F),
    muted = Color(0xFF7A746C),
)

/** Anthracite: neutral charcoal, sand bubbles, one cognac spark. Closest to Midnight. */
val AnthracitePalette = Palette(
    isDark = true,
    bg = Color(0xFF0E0E10),
    surface = Color(0xFF1B1B1F),
    line = Color(0xFF2E2E34),
    accent = Color(0xFFE7E3DD),
    onAccent = Color(0xFF1B1B1F),
    accentDim = Color(0xFF2A2A30),
    gilt = Color(0xFFC27A47),
    text = Color(0xFFE7E3DD),
    muted = Color(0xFF8B8B92),
)

/** A selectable theme. `id` is persisted; add more variants here later. */
enum class ThemeVariant(val id: String, val label: String, val palette: Palette) {
    MIDNIGHT("midnight", "Midnight", MidnightPalette),
    PORCELAIN("porcelain", "Porcelain", PorcelainPalette),
    ROUGE_NOIR("rouge_noir", "Rouge Noir", RougeNoirPalette),
    BLUE_CORAL("blue_coral", "Blue Coral", BlueCoralPalette),
    COGNAC("cognac", "Cognac", CognacPalette),
    WHITE_SAND("white_sand", "White Sand", WhiteSandPalette),
    ANTHRACITE("anthracite", "Anthracite", AnthracitePalette),
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

    fun apply(variant: ThemeVariant) = apply(variant.palette)

    /** Publish a raw palette; used for the crossfading palette during a theme switch. */
    fun apply(p: Palette) {
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

/**
 * One horizontal inset for every screen's content, so screens stop each picking their own.
 * Headers and rows both derive from this.
 */
val ScreenPadding = 16.dp
