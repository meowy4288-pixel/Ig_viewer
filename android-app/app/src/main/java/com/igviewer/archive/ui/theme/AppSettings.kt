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

    // Per-effect motion toggles (each additionally gated by [animations]).
    var animScreen by mutableStateOf(prefs.animScreen)
    var animThemeFade by mutableStateOf(prefs.animThemeFade)
    var animPress by mutableStateOf(prefs.animPress)
    var animListRise by mutableStateOf(prefs.animListRise)
    var animCascade by mutableStateOf(prefs.animCascade)
    var animDateTick by mutableStateOf(prefs.animDateTick)
    var animFlare by mutableStateOf(prefs.animFlare)
    var animJump by mutableStateOf(prefs.animJump)

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

    fun updateAnimScreen(value: Boolean) {
        animScreen = value
        prefs.animScreen = value
    }

    fun updateAnimThemeFade(value: Boolean) {
        animThemeFade = value
        prefs.animThemeFade = value
    }

    fun updateAnimPress(value: Boolean) {
        animPress = value
        prefs.animPress = value
    }

    fun updateAnimListRise(value: Boolean) {
        animListRise = value
        prefs.animListRise = value
    }

    fun updateAnimCascade(value: Boolean) {
        animCascade = value
        prefs.animCascade = value
    }

    fun updateAnimDateTick(value: Boolean) {
        animDateTick = value
        prefs.animDateTick = value
    }

    fun updateAnimFlare(value: Boolean) {
        animFlare = value
        prefs.animFlare = value
    }

    fun updateAnimJump(value: Boolean) {
        animJump = value
        prefs.animJump = value
    }
}

/**
 * The effective per-effect motion flags. Each field is already ANDed with the master
 * [AppSettings.animations] switch by the caller, so a helper can just read its flag.
 * Defaults are all-on so anything read before the provider mounts still animates.
 */
data class Motion(
    val screen: Boolean = true,
    val themeFade: Boolean = true,
    val press: Boolean = true,
    val listRise: Boolean = true,
    val cascade: Boolean = true,
    val dateTick: Boolean = true,
    val flare: Boolean = true,
    val jump: Boolean = true,
)

/** Effective per-effect motion flags, provided once at the root of the app. */
val LocalMotion = staticCompositionLocalOf { Motion() }

/** Tighter vertical padding in message rows and list rows. */
val LocalCompactRows = staticCompositionLocalOf { false }

/** When false, transition/animation durations collapse to zero. */
val LocalAnimations = staticCompositionLocalOf { true }
