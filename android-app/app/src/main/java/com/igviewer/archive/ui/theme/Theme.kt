package com.igviewer.archive.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun schemeFor(p: Palette) =
    if (p.isDark) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            secondary = p.gilt,
            onSecondary = p.onAccent,
            background = p.bg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.line,
            onSurfaceVariant = p.muted,
            outline = p.line,
            error = androidx.compose.ui.graphics.Color(0xFFE9A3B0),
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            secondary = p.gilt,
            onSecondary = p.onAccent,
            background = p.bg,
            onBackground = p.text,
            surface = p.surface,
            onSurface = p.text,
            surfaceVariant = p.line,
            onSurfaceVariant = p.muted,
            outline = p.line,
            error = androidx.compose.ui.graphics.Color(0xFF8F1D31),
        )
    }

@Composable
fun IGArchiveTheme(
    variant: ThemeVariant = ThemeVariant.MIDNIGHT,
    crossfade: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Publish the variant's palette into the app-wide tokens before anything reads it.
    // When crossfade is on, the palette animates per-colour instead of snapping.
    val pal = variant.palette.animated(crossfade)
    Tokens.apply(pal)
    val colors = schemeFor(pal)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !pal.isDark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
