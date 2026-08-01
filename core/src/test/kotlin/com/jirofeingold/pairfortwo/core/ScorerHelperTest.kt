package com.jirofeingold.pairfortwo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scorer's legality helpers. These are one-liners and carry no fixture corpus — the
 * differential fixtures cover the counting; this covers the rules the *engine* leans on when
 * deciding whose turn it is and whether a "go" is forced.
 */
class ScorerHelperTest {

    private fun c(rank: Int, suit: Suit) = Card(Rank.of(rank), suit)

    @Test
    fun `legalPlays excludes cards that would break 31`() {
        val hand = listOf(c(5, Suit.SPADES), c(10, Suit.HEARTS), c(13, Suit.CLUBS), c(2, Suit.DIAMONDS))
        // At 21, only cards worth 10 or less are legal — the king counts 10, so it fits exactly.
        assertEquals(hand, CribbageScorer.legalPlays(hand, count = 21))
        // At 26, only the five and the two fit.
        assertEquals(
            listOf(c(5, Suit.SPADES), c(2, Suit.DIAMONDS)),
            CribbageScorer.legalPlays(hand, count = 26),
        )
        assertEquals(emptyList<Card>(), CribbageScorer.legalPlays(hand, count = 30))
    }

    @Test
    fun `mustSayGo only when cards are held but none are playable`() {
        val hand = listOf(c(10, Suit.HEARTS))
        assertFalse(CribbageScorer.mustSayGo(hand, count = 21), "a ten at 21 makes exactly 31")
        assertTrue(CribbageScorer.mustSayGo(hand, count = 22))
        // An empty hand is not a "go" — the player is out of cards, which the engine treats
        // differently from being unable to play.
        assertFalse(CribbageScorer.mustSayGo(emptyList(), count = 30))
    }

    @Test
    fun `his heels is a jack starter and nothing else`() {
        assertTrue(CribbageScorer.isHisHeels(c(11, Suit.SPADES)))
        assertFalse(CribbageScorer.isHisHeels(c(12, Suit.SPADES)))
        assertFalse(CribbageScorer.isHisHeels(c(1, Suit.SPADES)))
    }

    @Test
    fun `totalPoints sums a flag list`() {
        val flags = CribbageScorer.handScore(
            hand = listOf(c(5, Suit.SPADES), c(5, Suit.HEARTS), c(5, Suit.DIAMONDS), c(11, Suit.CLUBS)),
            starter = c(5, Suit.CLUBS),
            isCrib = false,
        )
        assertEquals(29, flags.totalPoints, "the 29-hand")
    }
}
