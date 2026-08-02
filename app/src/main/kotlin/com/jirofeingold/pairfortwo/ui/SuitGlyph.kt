package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import com.jirofeingold.pairfortwo.core.Suit

/**
 * The four suit symbols, **drawn as vector paths** rather than rendered as text.
 *
 * ## Why not just use the ♠♥♦♣ characters
 *
 * iOS draws them as text and gets SF's text-style glyphs. Android does not have a comparable
 * guarantee: the symbols come from whichever font the vendor maps them to, the metrics differ
 * noticeably from iOS's, and on some devices they render as **colour emoji** — a red-and-pink
 * cartoon heart in the middle of a playing card. The first screenshot of the ported card showed
 * thick rounded glyphs large enough to collide with the centre pip, which is exactly the collision
 * the Swift comments say must not happen.
 *
 * Drawing them fixes the size, the weight and the shape on every device, and PLAN.md §6 specified
 * custom drawing for `CardView` for this reason.
 *
 * Paths are authored in a 100×100 box with y downward and scaled to the requested size.
 */
object SuitGlyph {

    /**
     * A drawn glyph's height as a fraction of the font size iOS asks for.
     *
     * The Swift sizes these as *text*, and a suit character occupies roughly 72% of its em box in
     * SF. So to land at the same visual size, a drawn glyph of `fontSize × 0.72` is the equivalent.
     *
     * This is a considered estimate, not a measurement against a running iOS build — worth
     * confirming with a side-by-side screenshot before the table is called finished.
     */
    const val EM_RATIO = 0.72f

    fun path(suit: Suit): Path = when (suit) {
        Suit.HEARTS -> heart()
        Suit.DIAMONDS -> diamond()
        Suit.SPADES -> spade()
        Suit.CLUBS -> club()
    }

    private fun heart() = Path().apply {
        moveTo(50f, 90f)
        cubicTo(25f, 71f, 4f, 54f, 4f, 34f)
        cubicTo(4f, 19f, 16f, 8f, 29f, 8f)
        cubicTo(39f, 8f, 46f, 14f, 50f, 21f)
        cubicTo(54f, 14f, 61f, 8f, 71f, 8f)
        cubicTo(84f, 8f, 96f, 19f, 96f, 34f)
        cubicTo(96f, 54f, 75f, 71f, 50f, 90f)
        close()
    }

    private fun diamond() = Path().apply {
        moveTo(50f, 4f)
        lineTo(90f, 50f)
        lineTo(50f, 96f)
        lineTo(10f, 50f)
        close()
    }

    private fun spade() = Path().apply {
        // An inverted heart with a stem.
        moveTo(50f, 6f)
        cubicTo(50f, 26f, 4f, 44f, 4f, 64f)
        cubicTo(4f, 77f, 14f, 86f, 26f, 86f)
        cubicTo(35f, 86f, 42f, 81f, 46f, 74f)
        cubicTo(45f, 84f, 41f, 91f, 34f, 96f)
        lineTo(66f, 96f)
        cubicTo(59f, 91f, 55f, 84f, 54f, 74f)
        cubicTo(58f, 81f, 65f, 86f, 74f, 86f)
        cubicTo(86f, 86f, 96f, 77f, 96f, 64f)
        cubicTo(96f, 44f, 50f, 26f, 50f, 6f)
        close()
    }

    private fun club() = Path().apply {
        // Three lobes over a stem.
        addOval(circle(50f, 26f, 22f))
        addOval(circle(26f, 62f, 22f))
        addOval(circle(74f, 62f, 22f))
        moveTo(44f, 60f)
        cubicTo(44f, 76f, 40f, 88f, 32f, 96f)
        lineTo(68f, 96f)
        cubicTo(60f, 88f, 56f, 76f, 56f, 60f)
        close()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r)

}

/** A suit symbol drawn at [size] square. */
@Composable
fun SuitSymbol(suit: Suit, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 100f
        scale(scaleX = s, scaleY = s, pivot = Offset.Zero) {
            drawPath(SuitGlyph.path(suit), color)
        }
    }
}
