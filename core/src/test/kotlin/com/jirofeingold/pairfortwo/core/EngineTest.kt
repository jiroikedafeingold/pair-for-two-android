package com.jirofeingold.pairfortwo.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine paths that [EngineFixtureTest] deliberately can't cover: everything that reshuffles.
 *
 * `dealNewHand`, the cut-for-deal tie recut and `playAgain` all draw from a seeded shuffle, and the
 * two platforms are not required to produce the same one — only the host deals, and it ships the
 * result in its snapshots (PLAN.md §0.3). So these are asserted **structurally**: the right number
 * of cards in the right places, the right phase, the right fields cleared.
 *
 * Also here: snapshot redaction at each phase boundary, which is the one place a bug would leak
 * the opponent's hand onto the wire.
 */
class EngineTest {

    private fun c(rank: Int, suit: Suit) = Card(Rank.of(rank), suit)

    private fun freshState(
        seed: ULong = 0x5EEDuL,
        scoringMode: ScoringMode = ScoringMode.OFF,
    ) = GameState.newMatch(
        matchID = "match-1",
        seed = seed,
        names = mapOf(PlayerID.ONE to "Jiro", PlayerID.TWO to "Sam"),
        colorIDs = mapOf(PlayerID.ONE to 2, PlayerID.TWO to 7),
        scoringMode = scoringMode,
    )

    // ---- Dealing ----

    @Test
    fun `dealNewHand deals six each and leaves forty in the deck`() {
        val s = freshState()
        CribbageEngine.dealNewHand(s)

        assertEquals(6, s.hands[PlayerID.ONE]!!.size)
        assertEquals(6, s.hands[PlayerID.TWO]!!.size)
        assertEquals(40, s.deck.cards.size)
        assertEquals(GamePhase.DISCARD_TO_CRIB, s.phase)
        assertEquals(1, s.handNumber)

        // No card may appear twice across the two hands and the remaining deck.
        val all = s.hands[PlayerID.ONE]!! + s.hands[PlayerID.TWO]!! + s.deck.cards
        assertEquals(52, all.size)
        assertEquals(52, all.toSet().size, "a card was dealt twice")
    }

    @Test
    fun `dealNewHand clears everything left over from the previous hand`() {
        val s = freshState()
        s.crib = listOf(c(5, Suit.SPADES))
        s.starter = c(11, Suit.HEARTS)
        s.discarded = setOf(PlayerID.ONE)
        s.starterCutIndex = 9
        s.starterCutLifted = true
        s.playSequence = listOf(PlayedCard(c(2, Suit.CLUBS), PlayerID.TWO))
        s.lapCards = listOf(c(2, Suit.CLUBS))
        s.goPlayers = setOf(PlayerID.TWO)
        s.lastToPlay = PlayerID.TWO
        s.whoseTurn = PlayerID.ONE
        s.activeFlags = listOf(ScoreFlag(ScoreFlag.Kind.GO, 1, "Go"))

        CribbageEngine.dealNewHand(s)

        assertTrue(s.crib.isEmpty())
        assertNull(s.starter)
        assertTrue(s.discarded.isEmpty())
        assertNull(s.starterCutIndex)
        assertFalse(s.starterCutLifted)
        assertTrue(s.playSequence.isEmpty())
        assertTrue(s.lapCards.isEmpty())
        assertTrue(s.goPlayers.isEmpty())
        assertNull(s.lastToPlay)
        assertNull(s.whoseTurn)
        assertTrue(s.activeFlags.isEmpty())
    }

    @Test
    fun `dealing is deterministic for a seed and varies by hand number`() {
        val a = freshState(seed = 42uL)
        val b = freshState(seed = 42uL)
        CribbageEngine.dealNewHand(a)
        CribbageEngine.dealNewHand(b)
        assertEquals(a.hands, b.hands, "the same seed must reproduce the same deal")

        // Hand two must not repeat hand one — the seed is combined with the hand number.
        val firstHand = a.hands
        CribbageEngine.dealNewHand(a)
        assertNotEquals(firstHand, a.hands)

        val other = freshState(seed = 43uL)
        CribbageEngine.dealNewHand(other)
        assertNotEquals(b.hands, other.hands, "different seeds must deal differently")
    }

    // ---- Cut for deal ----

    @Test
    fun `a genuine tie clears both cuts and reshuffles`() {
        val s = freshState()
        CribbageEngine.begin(s)
        // Two aces of different suits: same rank, different cards — a real tie.
        s.deck = Deck(listOf(c(1, Suit.SPADES), c(1, Suit.HEARTS)) + Deck.ordered().cards.drop(2))
        val seedBefore = s.seed
        val deckBefore = s.deck.cards

        assertTrue(CribbageEngine.cutForDeal(s, PlayerID.ONE, index = 0))
        assertTrue(CribbageEngine.cutForDeal(s, PlayerID.TWO, index = 1))

        assertTrue(s.cutForDeal.isEmpty(), "a tie must clear the cuts so both players recut")
        assertNotEquals(seedBefore, s.seed, "the seed must move so the reshuffle differs")
        assertNotEquals(deckBefore, s.deck.cards)
        assertEquals(GamePhase.CUT_FOR_DEAL, s.phase)
    }

    @Test
    fun `cutting the same index as the opponent takes a different card`() {
        val s = freshState()
        CribbageEngine.begin(s)
        // Index 3 and index 3+26 must differ in rank, or the collision resolves into a tie.
        val deck = MutableList(52) { c(1, Suit.SPADES) }
        Deck.ordered().cards.forEachIndexed { i, card -> deck[i] = card }
        deck[3] = c(4, Suit.SPADES)
        deck[29] = c(9, Suit.HEARTS)
        s.deck = Deck(deck)

        CribbageEngine.cutForDeal(s, PlayerID.ONE, index = 3)
        CribbageEngine.cutForDeal(s, PlayerID.TWO, index = 3)

        assertEquals(c(4, Suit.SPADES), s.cutForDeal[PlayerID.ONE])
        assertEquals(c(9, Suit.HEARTS), s.cutForDeal[PlayerID.TWO], "the collision must move the cut")
        assertEquals(PlayerID.ONE, s.dealer, "the lower card deals")
    }

    // ---- Advancing into a deal ----

    @Test
    fun `advance from handComplete passes the deal and deals again`() {
        val s = freshState()
        CribbageEngine.dealNewHand(s)
        s.phase = GamePhase.HAND_COMPLETE
        s.cutForDeal = mapOf(PlayerID.ONE to c(3, Suit.SPADES))
        val dealerBefore = s.dealer

        assertTrue(CribbageEngine.advance(s))

        assertEquals(dealerBefore.opponent, s.dealer, "the deal passes to the former pone")
        assertTrue(s.cutForDeal.isEmpty())
        assertEquals(GamePhase.DISCARD_TO_CRIB, s.phase)
        assertEquals(2, s.handNumber)
        assertEquals(6, s.hands[PlayerID.ONE]!!.size)
    }

    @Test
    fun `advance from an undecided cut for deal is refused`() {
        val s = freshState()
        CribbageEngine.begin(s)
        assertFalse(CribbageEngine.advance(s), "nobody has cut yet")
        CribbageEngine.cutForDeal(s, PlayerID.ONE, index = 0)
        assertFalse(CribbageEngine.advance(s), "only one player has cut")
    }

    // ---- Play again ----

    @Test
    fun `playAgain resets the score, flips the dealer and keeps the players`() {
        val s = freshState(scoringMode = ScoringMode.FEEDBACK)
        CribbageEngine.dealNewHand(s)
        s.dealer = PlayerID.TWO
        CribbageEngine.claim(s, PlayerID.ONE, 121)
        assertEquals(PlayerID.ONE, s.winner)

        val fresh = CribbageEngine.playAgain(s)

        assertEquals(0, fresh.scores[PlayerID.ONE])
        assertEquals(0, fresh.scores[PlayerID.TWO])
        assertNull(fresh.winner)
        assertTrue(fresh.claimHistory.isEmpty())
        assertEquals(0, fresh.claimTick)
        assertEquals(PlayerID.ONE, fresh.dealer, "the player who wasn't dealer last game deals first")
        // Straight to the deal — a rematch skips the cut for deal.
        assertEquals(GamePhase.DISCARD_TO_CRIB, fresh.phase)
        assertEquals(6, fresh.hands[PlayerID.ONE]!!.size)
        assertTrue(fresh.cutForDeal.isEmpty())

        assertEquals(s.matchID, fresh.matchID)
        assertEquals(s.names, fresh.names)
        assertEquals(s.colorIDs, fresh.colorIDs)
        assertEquals(ScoringMode.FEEDBACK, fresh.scoringMode)
    }

    // ---- Snapshot redaction ----

    @Test
    fun `the opponent's hand is never on the wire before the show`() {
        val s = freshState()
        CribbageEngine.dealNewHand(s)
        s.crib = listOf(c(2, Suit.SPADES), c(3, Suit.SPADES), c(4, Suit.SPADES), c(5, Suit.SPADES))

        for (phase in GamePhase.entries) {
            s.phase = phase
            val snap = s.snapshot(PlayerID.ONE)
            if (phase.revealRank < GamePhase.SHOW_PONE.revealRank) {
                assertNull(snap.opponentHand, "$phase leaked the opponent's hand")
            } else {
                assertEquals(s.hands[PlayerID.TWO], snap.opponentHand, "$phase should reveal hands")
            }
            assertEquals(
                if (phase.revealsCrib) s.crib else null,
                snap.crib,
                "$phase got the crib wrong",
            )
            // The count is always safe to send, and is what the table draws face-down cards from.
            assertEquals(6, snap.opponentHandCount)
        }
    }

    @Test
    fun `during pegging you see only your unplayed cards`() {
        val s = freshState()
        CribbageEngine.dealNewHand(s)
        s.hands = mapOf(
            PlayerID.ONE to listOf(c(1, Suit.SPADES), c(2, Suit.SPADES), c(3, Suit.SPADES), c(4, Suit.SPADES)),
            PlayerID.TWO to listOf(c(1, Suit.HEARTS), c(2, Suit.HEARTS), c(3, Suit.HEARTS), c(4, Suit.HEARTS)),
        )
        s.phase = GamePhase.PEGGING
        s.playSequence = listOf(
            PlayedCard(c(1, Suit.SPADES), PlayerID.ONE),
            PlayedCard(c(1, Suit.HEARTS), PlayerID.TWO),
        )

        val snap = s.snapshot(PlayerID.ONE)
        assertEquals(3, snap.yourHand.size)
        assertFalse(c(1, Suit.SPADES) in snap.yourHand, "a played card is still shown in hand")
        assertEquals(3, snap.opponentHandCount)

        // At the show the full four come back — they're needed to count.
        s.phase = GamePhase.SHOW_PONE
        assertEquals(4, s.snapshot(PlayerID.ONE).yourHand.size)
    }

    @Test
    fun `the score log only ships once the game is over`() {
        val s = freshState()
        CribbageEngine.dealNewHand(s)
        CribbageEngine.claim(s, PlayerID.ONE, 4)
        assertTrue(s.snapshot(PlayerID.ONE).scoreLog.isEmpty())

        CribbageEngine.claim(s, PlayerID.ONE, 121)
        assertEquals(GamePhase.GAME_OVER, s.phase)
        assertEquals(2, s.snapshot(PlayerID.ONE).scoreLog.size)
    }

    @Test
    fun `flags are withheld entirely when scoring is the player's responsibility`() {
        val s = freshState(scoringMode = ScoringMode.OFF)
        s.activeFlags = listOf(ScoreFlag(ScoreFlag.Kind.FIFTEEN, 2, "Fifteen 2"))
        assertTrue(s.snapshot(PlayerID.ONE).flags.isEmpty())

        s.scoringMode = ScoringMode.FEEDBACK
        assertEquals(1, s.snapshot(PlayerID.ONE).flags.size)
    }
}
