package com.igviewer.archive.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Motion helpers, adapted from the ThemesAndMotion reference. Every helper takes an
 * explicit `on` flag (read from [LocalMotion] at the call site), so each effect can be
 * toggled independently; when off, durations collapse to zero and effects are skipped.
 *
 * Personality: slow-out easing, short distances, springs only where something is
 * physically pressed. Nothing bounces for its own sake.
 */

/**
 * TEMPORARY master switch for the whole motion system.
 *
 * Set to false because the effects were not observable on the test device, so every call
 * site resolves to its "off" branch and the UI settles instantly. Flip this back to true
 * (and the Motion section in Settings, which is hidden while it is false) to re-enable
 * everything at once -- no other file needs to change.
 */
const val MOTION_ENABLED = false

val Slow = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun <T> t(ms: Int, on: Boolean, delay: Int = 0): TweenSpec<T> =
    tween(if (on) ms else 0, if (on) delay else 0, Slow)

/** Theme switching crossfades every colour instead of snapping. */
@Composable
fun Palette.animated(animationsOn: Boolean): Palette {
    val spec = tween<Color>(if (animationsOn) 450 else 0)
    @Composable fun Color.a() = animateColorAsState(this, spec, label = "pal").value
    return copy(
        bg = bg.a(),
        surface = surface.a(),
        line = line.a(),
        accent = accent.a(),
        onAccent = onAccent.a(),
        accentDim = accentDim.a(),
        gilt = gilt.a(),
        text = text.a(),
        muted = muted.a(),
    )
}

/** Screen transition: the new screen fades up from 97% scale, the old one dissolves fast. */
fun <S> veil(on: Boolean): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    (fadeIn(t(320, on, 70)) + scaleIn(t(320, on, 70), initialScale = 0.97f)) togetherWith
        fadeOut(t(150, on))
}

/** Press feedback: sinks to 95% and springs back. Replaces `.clickable` on rows/chips/tiles. */
fun Modifier.sinkOnPress(on: Boolean, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed && on) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "sink",
    )
    this
        .graphicsLayer {
            scaleX = s
            scaleY = s
        }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Rise-in for the chat list and Library grid: fade + 32dp drift, staggered (capped at 10). */
fun Modifier.riseIn(index: Int, on: Boolean): Modifier = composed {
    if (!on) return@composed this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        a.animateTo(1f, tween(420, delayMillis = index.coerceAtMost(10) * 40, easing = Slow))
    }
    this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 32.dp.toPx()
    }
}

/**
 * Message cascade for when a chat OPENS. Pass `order` = position among the first visible
 * messages (0..8) while the open-cascade flag is true, and null afterwards, so scrolling
 * through a huge chat never replays it. Bubbles settle in from 20dp low with a subtle scale.
 */
fun Modifier.settleIn(order: Int?, on: Boolean): Modifier = composed {
    if (!on || order == null) return@composed this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        a.animateTo(1f, tween(380, delayMillis = order.coerceIn(0, 8) * 35, easing = Slow))
    }
    this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 20.dp.toPx()
        val s = 0.97f + 0.03f * a.value
        scaleX = s
        scaleY = s
    }
}

/**
 * Date strip label: when the day changes (arrows, picker, scrolling) the old label slides
 * out and the new one slides in from the direction of travel.
 */
fun <S : Comparable<S>> dayTick(on: Boolean): AnimatedContentTransitionScope<S>.() -> ContentTransform = {
    val dir = if (targetState > initialState) 1 else -1
    (slideInVertically(t(260, on)) { it * dir / 2 } + fadeIn(t(220, on))) togetherWith
        (slideOutVertically(t(200, on)) { -it * dir / 2 } + fadeOut(t(120, on)))
}

/**
 * Highlight flare for a message jumped to (search / library / date): flares, gutters,
 * flares, then settles. Apply as
 * `Modifier.background(Tokens.AccentDim.copy(alpha = flare(isTarget, anim)))`.
 */
@Composable
fun flare(isTarget: Boolean, on: Boolean): Float {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(isTarget) {
        if (!isTarget) {
            glow.snapTo(0f)
        } else if (!on) {
            glow.snapTo(0.8f)
        } else {
            glow.animateTo(
                0.8f,
                keyframes {
                    durationMillis = 1400
                    1f at 180
                    0.5f at 520
                    1f at 800
                },
            )
        }
    }
    return glow.value
}

/** Jump buttons: spring pop in, quick shrink out. */
fun popIn(on: Boolean): EnterTransition =
    if (on) scaleIn(spring(dampingRatio = 0.5f, stiffness = 380f)) + fadeIn(tween(120)) else EnterTransition.None

fun popOut(on: Boolean): ExitTransition =
    if (on) scaleOut(tween(120)) + fadeOut(tween(120)) else ExitTransition.None
