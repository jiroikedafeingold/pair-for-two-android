package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jirofeingold.pairfortwo.core.PlayerSnapshot
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.playerTheme

/**
 * The shared table centre during pegging — port of the iOS `PlayPileView`.
 *
 * The cut card set off to the side (it counts for everyone's hands but is never played), the running
 * count, the sequence of played cards that both players can see, and a face-down crib indicator.
 */
@Composable
fun PlayPileView(
    snapshot: PlayerSnapshot,
    colorIDFor: (com.jirofeingold.pairfortwo.core.PlayerID) -> Int,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 60.dp,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(cardWidth * 0.5f),
        verticalAlignment = Alignment.Top,
    ) {
        CutStack(snapshot, cardWidth)
        PlayedStack(snapshot, colorIDFor, cardWidth)
        CribStack(snapshot, cardWidth)
    }
}

/** The cut card, off to the side: it counts for the hands but is never played. */
@Composable
private fun CutStack(snapshot: PlayerSnapshot, cardWidth: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption("The Cut")
        // A rank+suit tile once it's turned up: this card sits on its own at half the hand's width,
        // and a full pip card at that size reads as a cream rectangle. Face-down it's a real card,
        // since the back art is the whole point of it.
        val starter = snapshot.starter
        if (starter != null) {
            RankSuitTile(starter, width = cardWidth)
        } else {
            CardView(null, faceUp = false, width = cardWidth)
        }
    }
}

@Composable
private fun PlayedStack(
    snapshot: PlayerSnapshot,
    colorIDFor: (com.jirofeingold.pairfortwo.core.PlayerID) -> Int,
    cardWidth: Dp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The running count lives here, which frees the space above the play area for bigger cards.
        Text(
            "Count ${snapshot.runningCount}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )

        if (snapshot.playSequence.isEmpty()) {
            EmptyPlayArea(cardWidth)
        } else {
            // Cards from finished laps (the count reset via a go or a 31) stay full strength on the
            // table; a vertical divider separates them from the current lap, so what is still in
            // play is clear without greying anything out.
            val firstActive = snapshot.playSequence.size - snapshot.lapCardCount
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (firstActive > 0 && snapshot.lapCardCount > 0) {
                    LaneRow(snapshot.playSequence.take(firstActive), snapshot, colorIDFor, cardWidth)
                    LapDivider(cardWidth)
                    LaneRow(snapshot.playSequence.takeLast(snapshot.lapCardCount), snapshot, colorIDFor, cardWidth)
                } else {
                    LaneRow(snapshot.playSequence, snapshot, colorIDFor, cardWidth)
                }
            }
        }
    }
}

@Composable
private fun EmptyPlayArea(cardWidth: Dp) {
    val stroke = Color.White.copy(alpha = 0.25f)
    Box(
        Modifier
            .size(width = cardWidth * 2.2f, height = cardWidth * 1.45f)
            .drawBehind {
                drawRoundRect(
                    color = stroke,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Play area", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
    }
}

/**
 * A run of played cards as simplified rank+suit tiles, lightly overlapped so every rank stays
 * visible, each with a colour bar showing who played it.
 *
 * The overlap is far shallower than a hand's fan (0.28 of a card against 0.55): the point of this
 * row is that both players can read the sequence and add it up, so hiding half of each card to save
 * width defeats it.
 */
@Composable
private fun LaneRow(
    cards: List<com.jirofeingold.pairfortwo.core.PlayedCard>,
    snapshot: PlayerSnapshot,
    colorIDFor: (com.jirofeingold.pairfortwo.core.PlayerID) -> Int,
    cardWidth: Dp,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(-cardWidth * 0.28f)) {
        for (played in cards) {
            // Width-constrained on purpose: the owner's colour bar below asks to fill its parent, and
            // an unconstrained Box would hand it the whole remaining row — which stretched the bar
            // across the table, squeezed the crib stack to a one-letter-per-line column, and through
            // that squashed the hand underneath.
            Box(Modifier.width(cardWidth)) {
                RankSuitTile(
                    played.card,
                    isHighlighted = played == snapshot.playSequence.lastOrNull(),
                    width = cardWidth,
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = cardWidth * 0.12f, vertical = cardWidth * 0.05f)
                        .fillMaxWidth()
                        .height(maxOf(3.dp, cardWidth * 0.07f))
                        .background(
                            playerTheme(colorIDFor(played.player)).primary,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

/** The line delineating finished laps from the current one. */
@Composable
private fun LapDivider(cardWidth: Dp) {
    Box(
        Modifier
            .width(2.dp)
            .height(cardWidth * 1.35f)
            .background(CribGold.copy(alpha = 0.55f), RoundedCornerShape(1.dp)),
    )
}

@Composable
private fun CribStack(snapshot: PlayerSnapshot, cardWidth: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption("Crib")
        val crib = snapshot.crib
        Box(
            Modifier.alpha(if (snapshot.cribCount == 0 && crib == null) 0.3f else 1f),
        ) {
            if (crib != null) {   // revealed at the show
                Row(horizontalArrangement = Arrangement.spacedBy(-cardWidth * 0.6f)) {
                    for (card in crib) CardView(card, width = cardWidth)
                }
            } else {
                for (i in 0 until maxOf(snapshot.cribCount, 1)) {
                    CardView(
                        null,
                        faceUp = false,
                        width = cardWidth,
                        modifier = Modifier.offset(x = (i * 3).dp, y = (i * 3).dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = CribGold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}
