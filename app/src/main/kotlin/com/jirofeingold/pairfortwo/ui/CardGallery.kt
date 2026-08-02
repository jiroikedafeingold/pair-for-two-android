package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jirofeingold.pairfortwo.core.Card
import com.jirofeingold.pairfortwo.core.Rank
import com.jirofeingold.pairfortwo.core.Suit
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.FeltMid

/**
 * A gallery of every card state, on the felt.
 *
 * Scaffolding while the table is built (PLAN.md §10 phase 6): [CardView] is the atom every other
 * table view composes from, and "it compiles" says nothing at all about whether a card *looks*
 * right. This puts every rank, every suit, both inks, all three backs and each interaction state on
 * screen at once, so a rendering mistake is obvious rather than waiting to be noticed inside a game.
 *
 * Replaced by `GameTableScreen` once that exists.
 */
@Composable
fun CardGallery(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeltMid, FeltDark)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Label("States")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardView(Card(Rank.JACK, Suit.SPADES))
            CardView(Card(Rank.QUEEN, Suit.CLUBS), isSelected = true)
            CardView(Card(Rank.TEN, Suit.DIAMONDS), isHighlighted = true)
            CardView(Card(Rank.ACE, Suit.HEARTS), isDimmed = true)
        }

        Label("Backs")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (back in CardBack.entries) {
                CardView(null, faceUp = false, cardBackID = back.id)
            }
        }

        Label("Every rank — spades")
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (rank in Rank.entries) CardView(Card(rank, Suit.SPADES), width = 40.dp)
        }

        Label("Every rank — hearts")
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (rank in Rank.entries) CardView(Card(rank, Suit.HEARTS), width = 40.dp)
        }

        Label("All four suits")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (suit in Suit.entries) CardView(Card(Rank.TEN, suit))
        }

        Label("Scaling")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (w in listOf(32, 48, 64, 88, 110)) {
                CardView(Card(Rank.KING, Suit.HEARTS), width = w.dp)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        color = CribGold,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Preview(showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun CardGalleryPreview() {
    CardGallery()
}
