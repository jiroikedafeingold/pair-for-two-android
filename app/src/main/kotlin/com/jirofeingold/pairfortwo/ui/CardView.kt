package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.jirofeingold.pairfortwo.core.Card
import com.jirofeingold.pairfortwo.core.Rank
import com.jirofeingold.pairfortwo.core.Suit
import com.jirofeingold.pairfortwo.ui.theme.CardFace
import com.jirofeingold.pairfortwo.ui.theme.CardInk
import com.jirofeingold.pairfortwo.ui.theme.CardRed
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark

/**
 * A classy, easy-to-read playing card — port of the iOS `CardView`.
 *
 * Cream face, rounded corners, hairline inner border, soft shadow; heavy rank+suit in opposite
 * corners and a large centre suit glyph. Pass `card = null` or `faceUp = false` for the face-down
 * back. Every dimension is a ratio of [width], exactly as the Swift does, so the card scales with
 * the table rather than needing size buckets.
 *
 * ## Two unavoidable differences from iOS
 *
 * - **Corner curve.** SwiftUI's `.continuous` corner style is a squircle; Compose's
 *   `RoundedCornerShape` is a plain circular arc. At a card's corner radius the difference is a
 *   fraction of a pixel of curvature and invisible side by side — not worth a custom `Shape`.
 * - **Typeface.** The Swift asks for SF Rounded Heavy, which has no Android equivalent. The
 *   closest honest match is the platform font at [FontWeight.Black]; the intent — letters that stay
 *   unambiguous at small sizes, especially J/Q/K — is preserved even though the shapes differ.
 */
@Composable
fun CardView(
    card: Card?,
    modifier: Modifier = Modifier,
    faceUp: Boolean = true,
    isSelected: Boolean = false,
    isDimmed: Boolean = false,
    isHighlighted: Boolean = false,
    width: Dp = 72.dp,
    cardBackID: Int = LocalCardBackID.current,
) {
    val height = width * 1.45f
    val corner = width * 0.13f
    val shape = RoundedCornerShape(corner)

    // The lift when a card is picked for the crib. Same spring as iOS: response 0.3, damping 0.75.
    val lift by animateDpAsState(
        targetValue = if (isSelected) -width * 0.22f else 0.dp,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "cardLift",
    )

    val description = if (faceUp && card != null) card.accessibleName else "Face-down card"

    Box(
        modifier = modifier
            .offset(y = lift)
            .alpha(if (isDimmed) 0.4f else 1f)
            .size(width = width, height = height)
            .shadow(elevation = width * 0.05f, shape = shape, clip = false)
            .clip(shape)
            .background(if (faceUp) CardFace else Color.Transparent)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (faceUp && card != null) {
            CardFaceContent(card, width)
        } else {
            CardBackContent(cardBackID, width, height, shape)
        }

        // Thin dark inner edge so the gold rim reads cleanly against the cream face.
        Box(
            Modifier
                .fillMaxSize()
                .border(0.75.dp, Color.Black.copy(alpha = 0.15f), shape),
        )

        // Gold rim on every card so it stands out from the felt and from adjacent cards; brighter
        // and thicker when highlighted (the most recent play, the winning cut).
        Box(
            Modifier
                .fillMaxSize()
                .border(
                    width = if (isHighlighted) 3.dp else 1.5.dp,
                    color = CribGold.copy(alpha = if (isHighlighted) 1f else 0.85f),
                    shape = shape,
                ),
        )
    }
}

@Composable
private fun CardFaceContent(card: Card, width: Dp) {
    val ink = if (card.suit.isRed) CardRed else CardInk

    Box(Modifier.fillMaxSize()) {
        CornerIndex(
            card, ink, width,
            Modifier
                .align(Alignment.TopStart)
                .padding(width * 0.09f),
        )

        // Centre pip, sized to sit clearly inside the middle so it never collides with the corner
        // indices — especially the wider two-character "10". The Swift's 0.44 is a *font size*, so
        // the drawn equivalent is that times the glyph's share of its em box.
        SuitSymbol(
            suit = card.suit,
            color = ink.copy(alpha = 0.92f),
            size = width * (0.44f * SuitGlyph.EM_RATIO),
            modifier = Modifier.align(Alignment.Center),
        )

        CornerIndex(
            card, ink, width,
            Modifier
                .align(Alignment.BottomEnd)
                .padding(width * 0.09f)
                .rotate(180f),
        )
    }
}

/**
 * The rank-over-suit corner index. Negative letter spacing keeps the two-character "10" compact so
 * it doesn't creep toward the centre pip — the same reason the Swift applies negative kerning.
 */
@Composable
private fun CornerIndex(card: Card, ink: Color, width: Dp, modifier: Modifier = Modifier) {
    val rankSize = width.value * 0.30f
    Column(
        modifier = modifier,
        // Leading, matching the Swift's `VStack(alignment: .leading)`.
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(-width * 0.02f),
    ) {
        Text(
            text = card.rank.label,
            color = ink,
            maxLines = 1,
            textAlign = TextAlign.Start,
            style = TextStyle(
                fontSize = rankSize.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-width.value * 0.012f).sp,
                // Compose pads every Text with the font's ascent/descent by default and lays it out
                // on a default line height. Left alone, the index occupies far more of the card than
                // the equivalent SwiftUI text and collides with the centre pip — which the Swift
                // explicitly sizes to avoid. Trimming both is what makes the two layouts agree.
                lineHeight = rankSize.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = TightLineHeight,
            ),
        )
        SuitSymbol(
            suit = card.suit,
            color = ink,
            size = width * (0.22f * SuitGlyph.EM_RATIO),
        )
    }
}

/**
 * The back design, shown whole (`Fit`) so none of the art is cropped, over a blurred copy that
 * fills the side margins — the designs are taller than a card, so a fit alone leaves gaps.
 *
 * `Modifier.blur` is a no-op below API 31. There the margins simply show the unblurred crop, which
 * is a cosmetic difference on old devices rather than a broken layout.
 */
@Composable
private fun CardBackContent(cardBackID: Int, width: Dp, height: Dp, shape: RoundedCornerShape) {
    val painter = painterResource(CardBack.from(cardBackID).res)
    Box(
        Modifier
            .size(width = width, height = height)
            .clip(shape),
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(width * 0.06f),
        )
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---- Previews ----

@Preview(name = "Cards on felt", showBackground = true, backgroundColor = 0xFF0D211A)
@Composable
private fun CardViewPreview() {
    Row(
        Modifier
            .background(FeltDark)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CardView(Card(Rank.JACK, Suit.SPADES))
        CardView(Card(Rank.JACK, Suit.HEARTS))
        CardView(Card(Rank.QUEEN, Suit.CLUBS), isSelected = true)
        CardView(Card(Rank.TEN, Suit.DIAMONDS), isHighlighted = true)
        CardView(Card(Rank.ACE, Suit.SPADES), isDimmed = true)
        CardView(null, faceUp = false)
    }
}

@Preview(name = "Every rank", showBackground = true, backgroundColor = 0xFF0D211A)
@Composable
private fun EveryRankPreview() {
    Column(
        Modifier
            .background(FeltDark)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (suit in listOf(Suit.SPADES, Suit.HEARTS)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (rank in Rank.entries) {
                    CardView(Card(rank, suit), width = 44.dp)
                }
            }
        }
    }
}
