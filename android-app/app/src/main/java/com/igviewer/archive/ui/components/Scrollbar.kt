package com.igviewer.archive.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A fast-scroll gutter for the app's long lists: a draggable thumb that maps its position
 * onto the list, plus a visible track.
 *
 * Compose's lazy lists ship no scrollbar of their own, and an archive viewer is precisely the
 * kind of app you want to fling through quickly, so this provides one. Written from scratch
 * because the app builds fully offline and cannot take a dependency for it.
 *
 * The gesture is observed on [PointerEventPass.Initial], *before* the list gets a chance to
 * scroll. A lazy list consumes vertical drags itself, so a detector on the default pass never
 * sees them and the thumb simply would not move. Touches are only consumed while the finger is
 * inside the gutter, so ordinary scrolling and row taps are untouched.
 */
private val ThumbWidth: Dp = 7.dp
private val GutterHit: Dp = 44.dp
private val Inset: Dp = 3.dp
private val MinThumb: Dp = 48.dp

private fun LazyListState.scrollToFraction(fraction: Float) {
    val count = layoutInfo.totalItemsCount
    if (count <= 0) return
    val target = (fraction.coerceIn(0f, 1f) * (count - 1)).toInt()
    if (target != firstVisibleItemIndex) requestScrollToItem(target)
}

private fun LazyGridState.scrollToFraction(fraction: Float) {
    val count = layoutInfo.totalItemsCount
    if (count <= 0) return
    val target = (fraction.coerceIn(0f, 1f) * (count - 1)).toInt()
    if (target != firstVisibleItemIndex) requestScrollToItem(target)
}

/**
 * Watches the gutter for a press/drag and reports the touched fraction of the height.
 * Consumes the gesture only once the finger is actually inside the gutter.
 */
private fun Modifier.gutterDrag(
    hitPx: Float,
    onFraction: (Float) -> Unit,
): Modifier = pointerInput(hitPx, onFraction, PointerEventPass.Initial) {
    awaitPointerEventScope {
        var dragging = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.pressed }
            if (change == null) {
                dragging = false
                continue
            }
            val inGutter = change.position.x >= size.width - hitPx
            when (event.type) {
                PointerEventType.Press -> {
                    dragging = inGutter
                    if (inGutter) {
                        change.consume()
                        onFraction(change.position.y / size.height)
                    }
                }
                PointerEventType.Move -> if (dragging && inGutter) {
                    change.consume()
                    onFraction(change.position.y / size.height)
                }
                PointerEventType.Release, PointerEventType.Exit -> dragging = false
                else -> Unit
            }
        }
    }
}

private fun Modifier.drawScrollThumb(
    first: Float,
    last: Float,
    total: Float,
    color: Color,
    density: androidx.compose.ui.unit.Density,
): Modifier = drawWithContent {
    drawContent()
    if (total <= 0f) return@drawWithContent
    val w = with(density) { ThumbWidth.toPx() }
    val inset = with(density) { Inset.toPx() }
    val minThumb = with(density) { MinThumb.toPx() }
    val height = size.height
    val fraction = ((last - first).coerceAtLeast(1f)) / total
    val thumbHeight = (height * fraction).coerceIn(minThumb, height)
    val top = (height * (first / total)).coerceIn(0f, height - thumbHeight)
    val left = size.width - w - inset

    drawRoundRect(
        color = color.copy(alpha = 0.16f),
        topLeft = Offset(left - inset, 0f),
        size = Size(w + inset * 2, height),
        cornerRadius = CornerRadius((w + inset * 2) / 2f, (w + inset * 2) / 2f),
    )
    drawRoundRect(
        color = color.copy(alpha = 0.85f),
        topLeft = Offset(left, top),
        size = Size(w, thumbHeight),
        cornerRadius = CornerRadius(w / 2f, w / 2f),
    )
}

/** Draggable fast scroll for a vertical [LazyListState]. */
fun Modifier.fastScroll(state: LazyListState, color: Color): Modifier = composed {
    val density = LocalDensity.current
    val hit = with(density) { GutterHit.toPx() }
    val extra = Modifier
        .drawScrollThumb(
            state.layoutInfo.visibleItemsInfo.firstOrNull()?.index?.toFloat() ?: 0f,
            (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0).toFloat() + 1f,
            state.layoutInfo.totalItemsCount.toFloat(),
            color,
            density,
        )
        .gutterDrag(hit) { fraction -> state.scrollToFraction(fraction) }
    this.then(extra)
}

/** Draggable fast scroll for a vertical [LazyGridState]. */
fun Modifier.fastScroll(state: LazyGridState, color: Color): Modifier = composed {
    val density = LocalDensity.current
    val hit = with(density) { GutterHit.toPx() }
    val extra = Modifier
        .drawScrollThumb(
            state.layoutInfo.visibleItemsInfo.firstOrNull()?.index?.toFloat() ?: 0f,
            (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0).toFloat() + 1f,
            state.layoutInfo.totalItemsCount.toFloat(),
            color,
            density,
        )
        .gutterDrag(hit) { fraction -> state.scrollToFraction(fraction) }
    this.then(extra)
}