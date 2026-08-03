package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * The cribbage progress loop that rides a panel's rounded edge — port of iOS's `ScoreTrackOverlay`.
 *
 * Each player's colour fills around the oval as they climb toward 121, closing into a complete ring
 * at game point. Subtle by design: it frames the numbers without competing with them.
 *
 * The skunk lines are marked too — 60 (double skunk) and 90 (skunk) — with literal little skunks
 * sitting on the track, so a player can see at a glance how close they are to being skunked.
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

    // Two skunks at the double-skunk line, one at the skunk line. Laid out once and reused every
    // frame — measuring text on each draw would be wasteful for two static glyphs.
    val measurer = rememberTextMeasurer()
    val doubleSkunk = rememberSkunkGlyphs(measurer, count = 2)
    val singleSkunk = rememberSkunkGlyphs(measurer, count = 1)

    Canvas(modifier) {
        val radiusPx = cornerRadius.toPx()

        drawScoreLoop(you, youColor, radiusPx, inset = 3.dp.toPx(), lineWidth = 2.dp.toPx())
        if (opponentFraction != null) {
            drawScoreLoop(opponent, opponentColor, radiusPx, inset = 8.5.dp.toPx(), lineWidth = 1.75.dp.toPx())
        }

        // The tick at the bottom middle where the loop starts and finishes: the 0 / 121 point.
        // Deliberately faint — it marks the seam, it isn't a score in its own right, and at the
        // old weight it read as a third thing competing with the two loops.
        val tickHeight = if (long) 10.dp.toPx() else 6.dp.toPx()
        val tickWidth = 1.5.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = Offset(size.width / 2 - tickWidth / 2, size.height - tickHeight),
            size = Size(tickWidth, tickHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(tickWidth / 2),
        )

        drawSkunkMark(60f / 121f, radiusPx, doubleSkunk)
        drawSkunkMark(90f / 121f, radiusPx, singleSkunk)
    }
}

/** Font size of one skunk on the track. Matches the iOS `SkunkMark.glyphSize`. */
private val SkunkGlyphSize = 13.sp

/**
 * [count] skunk glyphs, measured once, overlapping slightly so a pair reads as one mark rather than
 * two separate ones.
 *
 * The overlap is negative letter spacing rather than iOS's negative `HStack` spacing — the same
 * -0.32 of the glyph size, expressed the way a single laid-out string can express it.
 */
@Composable
private fun rememberSkunkGlyphs(measurer: TextMeasurer, count: Int): TextLayoutResult =
    remember(measurer, count) {
        measurer.measure(
            "🦨".repeat(count),
            style = TextStyle(
                fontSize = SkunkGlyphSize,
                letterSpacing = (SkunkGlyphSize.value * -0.32f).sp,
            ),
        )
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
 * [glyphs] centred on the point [fraction] of the way round the track.
 *
 * The position comes from the same path the loops use, so the mark sits right on the ring. iOS has
 * to approximate that point from the bounding box of a tiny trimmed slice; Compose's `PathMeasure`
 * gives it directly.
 *
 * Half-transparent: these are a reference mark on a scoreboard, not decoration to be read first.
 */
private fun DrawScope.drawSkunkMark(
    fraction: Float,
    cornerRadius: Float,
    glyphs: TextLayoutResult,
) {
    val path = trackPath(size, cornerRadius, inset = 5.5.dp.toPx())
    val measure = PathMeasure().apply { setPath(path, false) }
    val point = measure.getPosition(measure.length * fraction)
    drawText(
        glyphs,
        topLeft = Offset(
            point.x - glyphs.size.width / 2f,
            point.y - glyphs.size.height / 2f,
        ),
        alpha = 0.5f,
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
