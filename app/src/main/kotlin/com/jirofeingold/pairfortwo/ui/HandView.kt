package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jirofeingold.pairfortwo.core.Card
import com.jirofeingold.pairfortwo.core.Rank
import com.jirofeingold.pairfortwo.core.Suit
import com.jirofeingold.pairfortwo.core.sortedForDisplay
import com.jirofeingold.pairfortwo.ui.theme.FeltMid
import kotlinx.coroutines.delay

/**
 * The current player's hand as a centred row — port of the iOS `HandView`.
 *
 * During the discard it selects up to two cards; during pegging it plays one, dimming illegal
 * (would-exceed-31) plays.
 *
 * @param dealSignal when non-null, the cards deal in one by one — dropping from above — whenever
 *   this value changes. Left null during pegging so cards don't re-animate on every play.
 */
@Composable
fun HandView(
    cards: List<Card>,
    onTap: (Card) -> Unit,
    modifier: Modifier = Modifier,
    selected: Set<Card> = emptySet(),
    isEnabled: (Card) -> Boolean = { true },
    cardWidth: Dp = 74.dp,
    dealSignal: Any? = null,
    /**
     * An optional colour tag under a card — the crib uses it to mark who discarded each card, which
     * is the only place at the show where whose card it was still matters.
     */
    marker: (Card) -> Color? = { null },
) {
    var revealed by remember { mutableIntStateOf(0) }

    LaunchedEffect(dealSignal) {
        if (dealSignal == null) return@LaunchedEffect
        revealed = 0
        delay(140)
        for (i in 1..maxOf(cards.size, 1)) {
            revealed = i
            delay(105)
        }
    }

    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(cardWidth * 0.18f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cards.forEachIndexed { idx, card ->
            val tag = marker(card)
            val shown = dealSignal == null || idx < revealed
            // The deal-in: each card drops from above, slightly rotated, alternating direction.
            val progress by animateFloatAsState(
                targetValue = if (shown) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                label = "dealIn",
            )
            val enabled = isEnabled(card)
            // The marker sits under the card rather than on it: the faces are already busy, and at
            // the show the crib's four cards are the only place ownership is worth reading.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            CardView(
                card = card,
                isSelected = card in selected,
                isDimmed = !enabled,
                width = cardWidth,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = progress
                        // Anchored at the top so the card appears to fall into place.
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        scaleX = 0.5f + 0.5f * progress
                        scaleY = 0.5f + 0.5f * progress
                        translationY = (1f - progress) * -80.dp.toPx()
                        rotationZ = (1f - progress) * (if (idx % 2 == 0) -12f else 12f)
                    }
                    .clickable(
                        // A card that is dimmed for being illegal is still tappable when it is
                        // already selected, so a discard pick can always be undone.
                        enabled = shown && (enabled || card in selected),
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTap(card) },
            )
                if (tag != null) {
                    Box(
                        Modifier
                            .graphicsLayer { alpha = progress }
                            .size(width = cardWidth * 0.5f, height = 4.dp)
                            .background(tag, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF173326, widthDp = 420, heightDp = 160)
@Composable
private fun HandViewPreview() {
    val cards = listOf(
        Card(Rank.ACE, Suit.SPADES),
        Card(Rank.FIVE, Suit.HEARTS),
        Card(Rank.JACK, Suit.CLUBS),
        Card(Rank.TEN, Suit.DIAMONDS),
    ).sortedForDisplay()
    Row(
        Modifier
            .background(FeltMid)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HandView(
            cards = cards,
            selected = setOf(Card(Rank.FIVE, Suit.HEARTS)),
            onTap = {},
        )
    }
}
