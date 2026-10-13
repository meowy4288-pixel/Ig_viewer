package com.igviewer.archive.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.igviewer.archive.data.Prefs

/**
 * Live, persisted appearance/behaviour settings. Every field is snapshot state, so
 * flipping one in Settings recomposes whatever reads it. Writes go straight through
 * to [Prefs] (app-private SharedPreferences -- never the export folder).
 */
class AppSettings(private val prefs: Prefs) {
    var themeId by mutableStateOf(prefs.themeId)
    var textScale by mutableStateOf(prefs.textScale)
    var compactRows by mutableStateOf(prefs.compactRows)
    var animations by mutableStateOf(prefs.animations)
    var sfxEnabled by mutableStateOf(prefs.sfxEnabled)
    var autoplayVideos by mutableStateOf(prefs.autoplayVideos)

    var theme: ThemeVariant
        get() = ThemeVariant.byId(themeId)
        set(value) {
            themeId = value.id
            prefs.themeId = value.id
        }

    fun updateTextScale(value: Float) {
        textScale = value
        prefs.textScale = value
    }

    fun updateCompactRows(value: Boolean) {
        compactRows = value
        prefs.compactRows = value
    }

    fun updateAnimations(value: Boolean) {
        animations = value
        prefs.animations = value
    }

    fun updateSfxEnabled(value: Boolean) {
        sfxEnabled = value
        prefs.sfxEnabled = value
    }

    fun updateAutoplayVideos(value: Boolean) {
        autoplayVideos = value
        prefs.autoplayVideos = value
    }
}

/** Tighter vertical padding in message rows and list rows. */
val LocalCompactRows = staticCompositionLocalOf { false }

/** When false, transition/animation durations collapse to zero. */
val LocalAnimations = staticCompositionLocalOf { true }
