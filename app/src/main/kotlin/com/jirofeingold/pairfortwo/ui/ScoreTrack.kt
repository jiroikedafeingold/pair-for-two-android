package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The cribbage progress loop that rides a panel's rounded edge — port of iOS's `ScoreTrackOverlay`.
 *
 * Each player's colour fills around the oval as they climb toward 121, closing into a complete ring
 * at game point. Subtle by design: it frames the numbers without competing with them.
 *
 * The skunk lines are marked too — 60 (double skunk) and 90 (skunk) — as short ticks laid across
 * the track, so a player can see at a glance how close they are to being skunked.
 */
@Composable
fun ScoreTrackOverlay(
    youFraction: Float,
    youColor: Color,
    modifier: Modifier = Modifier,
    /** Null draws a single loop. A value nests an opponent loop just inside it. */
    opponentFraction: Float? = null,
    opponentColor: Color = Color.Gray,
    cornerRadius: Dp = 22.dp,
) {
    val you by animateFloatAsState(youFraction, tween(500), label = "youTrack")
    val opponent by animateFloatAsState(opponentFraction ?: 0f, tween(500), label = "oppTrack")
    val long = opponentFraction != null

    Canvas(modifier) {
        val radiusPx = cornerRadius.toPx()

        drawScoreLoop(you, youColor, radiusPx, inset = 3.dp.toPx(), lineWidth = 2.dp.toPx())
        if (opponentFraction != null) {
            drawScoreLoop(opponent, opponentColor, radiusPx, inset = 8.5.dp.toPx(), lineWidth = 1.75.dp.toPx())
        }

        // The tick at the bottom middle where the loop starts and finishes: the 0 / 121 point.
        val tickHeight = if (long) 13.dp.toPx() else 8.dp.toPx()
        val tickWidth = 2.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.5f),
            topLeft = Offset(size.width / 2 - tickWidth / 2, size.height - tickHeight),
            size = Size(tickWidth, tickHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(tickWidth / 2),
        )

        for (fraction in listOf(60f / 121f, 90f / 121f)) {
            drawSkunkTick(fraction, radiusPx, long)
        }
    }
}

/**
 * A faint full-loop track with the filled portion over it, so the remaining distance to 121 stays
 * visible. The fill glows, brightening as it closes into a ring at game point.
 */
private fun DrawScope.drawScoreLoop(
    fraction: Float,
    color: Color,
    cornerRadius: Float,
    inset: Float,
    lineWidth: Float,
) {
    val path = trackPath(size, cornerRadius, inset)
    drawPath(path, color.copy(alpha = 0.12f), style = Stroke(lineWidth))

    val clamped = fraction.coerceIn(0f, 1f)
    if (clamped <= 0f) return

    val measure = PathMeasure().apply { setPath(path, false) }
    val filled = Path()
    measure.getSegment(0f, measure.length * clamped, filled, true)
    drawPath(
        filled,
        color.copy(alpha = 0.9f),
        style = Stroke(lineWidth, cap = StrokeCap.Round),
    )
}

/**
 * A short line crossing the track at [fraction] of the way round.
 *
 * The point and its tangent come from the same path the loops use, so the mark lines up with the
 * fill. iOS has to approximate this from the bounding boxes of tiny trimmed slices; Compose's
 * `PathMeasure` gives position and tangent directly.
 */
private fun DrawScope.drawSkunkTick(fraction: Float, cornerRadius: Float, long: Boolean) {
    val path = trackPath(size, cornerRadius, inset = 5.5.dp.toPx())
    val measure = PathMeasure().apply { setPath(path, false) }
    val distance = measure.length * fraction
    val point = measure.getPosition(distance)
    val tangent = measure.getTangent(distance)
    // Unit normal — perpendicular to the track, so the tick lies across it.
    val nx = -tangent.y
    val ny = tangent.x
    val half = if (long) 5.dp.toPx() else 3.dp.toPx()
    drawLine(
        color = Color.White.copy(alpha = 0.28f),
        start = Offset(point.x - nx * half, point.y - ny * half),
        end = Offset(point.x + nx * half, point.y + ny * half),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/**
 * The rounded-rectangle perimeter, **starting at the bottom middle and winding counter-clockwise**,
 * so trimming it fills the loop from the bottom outward the way a score climbs.
 *
 * Neither platform's built-in rounded rectangle starts there — SwiftUI's begins near the top and
 * runs clockwise — so both apps build the path by hand, and this follows the Swift corner for
 * corner.
 */
private fun trackPath(size: Size, cornerRadius: Float, inset: Float): Path {
    val r = Rect(inset, inset, size.width - inset, size.height - inset)
    val rad = max(0f, min(cornerRadius - inset, min(r.width, r.height) / 2f))
    return Path().apply {
        moveTo(r.center.x, r.bottom)
        // Right along the bottom, then up the right side, across the top, down the left, and back.
        lineTo(r.right - rad, r.bottom)
        arcTo(Rect(r.right - 2 * rad, r.bottom - 2 * rad, r.right, r.bottom), 90f, -90f, false)
        lineTo(r.right, r.top + rad)
        arcTo(Rect(r.right - 2 * rad, r.top, r.right, r.top + 2 * rad), 0f, -90f, false)
        lineTo(r.left + rad, r.top)
        arcTo(Rect(r.left, r.top, r.left + 2 * rad, r.top + 2 * rad), 270f, -90f, false)
        lineTo(r.left, r.bottom - rad)
        arcTo(Rect(r.left, r.bottom - 2 * rad, r.left + 2 * rad, r.bottom), 180f, -90f, false)
        lineTo(r.center.x, r.bottom)
    }
}

/** How much of the loop a score fills: 0 at the start, a full loop at the 121 game point. */
fun loopFraction(points: Int): Float = (points / 121f).coerceIn(0f, 1f)
