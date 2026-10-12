package com.igviewer.archive.ui.components

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape

/**
 * The tombstone/arch avatar from DESIGN.html:
 * a semicircular top over a rectangle with slightly rounded bottom corners.
 * Corner radius is expressed as a fraction of width (~4dp on a 36dp avatar)
 * because [GenericShape] builders have no Density context.
 */
val ArchShape: Shape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val r = w / 2f
    val b = (w * 0.11f).coerceAtMost(h / 2f).coerceAtMost(r)

    moveTo(0f, h - b)
    lineTo(0f, r)
    arcTo(
        rect = Rect(0f, 0f, w, w),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false,
    )
    lineTo(w, h - b)
    quadraticBezierTo(w, h, w - b, h)
    lineTo(b, h)
    quadraticBezierTo(0f, h, 0f, h - b)
    close()
}
