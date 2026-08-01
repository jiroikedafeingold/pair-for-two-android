package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.Serializable

/**
 * The single source of truth for a match — port of the iOS `GameState`.
 *
 * Lives **only on the host referee**. Guests never see this; they receive redacted
 * [PlayerSnapshot]s. The host persists it for resume.
 *
 * ## Why this is a mutable class and not a `data class`
 *
 * The Swift is a `struct` mutated through `inout` by `CribbageEngine`, which touches a dozen
 * fields per intent. Threading `copy()` calls through that would be a rewrite, not a port, and
 * this is the file where a port that reads differently from its original is most likely to hide
 * a bug. So: `var` fields mutated in place, matching the Swift statement for statement.
 *
 * The fields themselves hold **immutable** collections that are reassigned rather than mutated,
 * which keeps Swift's per-field value semantics — a `List` handed to a snapshot can't be changed
 * underneath it later.
 *
 * The wire never carries this type (PLAN.md §2), only [PlayerSnapshot], so its serialized shape
 * is a local-disk concern and need not match Swift's byte for byte.
 */
@Serializable
class GameState(
    var matchID: String,
    var phase: GamePhase = GamePhase.CONNECTING,
    var handNumber: Int = 0,
    var scoringMode: ScoringMode = ScoringMode.OFF,

    // Players
    var names: Map<PlayerID, String> = emptyMap(),
    var colorIDs: Map<PlayerID, Int> = emptyMap(),
    var scores: Map<PlayerID, Int> = mapOf(PlayerID.ONE to 0, PlayerID.TWO to 0),

    // Deal
    var dealer: PlayerID = PlayerID.ONE,
    var seed: ULong = 0uL,
    var deck: Deck = Deck.ordered(),
    var hands: Map<PlayerID, List<Card>> = mapOf(PlayerID.ONE to emptyList(), PlayerID.TWO to emptyList()),
    var crib: List<Card> = emptyList(),
    var starter: Card? = null,
    var discarded: Set<PlayerID> = emptySet(),

    /**
     * Starter cut, a manual two-step that mirrors an in-person cut: the pone lifts the deck
     * (recording the index), then the dealer reveals the card at that depth.
     */
    var starterCutIndex: Int? = null,
    var starterCutLifted: Boolean = false,

    var cutForDeal: Map<PlayerID, Card> = emptyMap(),

    // Pegging
    /** Full play history for the current hand; both players see it. */
    var playSequence: List<PlayedCard> = emptyList(),
    /** Cards played since the last reset (a go or a 31). */
    var lapCards: List<Card> = emptyList(),
    var whoseTurn: PlayerID? = null,
    /** Who has said "go" in the current lap. */
    var goPlayers: Set<PlayerID> = emptySet(),
    /** Who laid the most recent card — for last-card and for the next lead. */
    var lastToPlay: PlayerID? = null,

    // Scoring assist (surfaced to the coach UI; only auto-applied in ScoringMode.AUTO)
    var activeFlags: List<ScoreFlag> = emptyList(),
    var claimHistory: List<Claim> = emptyList(),
    /** Increments on each claim, so devices can preview "+X". */
    var claimTick: Int = 0,

    /** Bumps on each go/31 so the other device can prompt "take the score". */
    var pegEventTick: Int = 0,
    var lastPegEvent: PegEvent? = null,

    var winner: PlayerID? = null,
) {

    /** The pone is always the dealer's opponent. */
    val pone: PlayerID get() = dealer.opponent

    /** Running pegging count for the current lap. */
    val runningCount: Int get() = lapCards.sumOf { it.countingValue }

    /** The seat a given player holds this hand. */
    fun seat(of: PlayerID): Seat = if (of == dealer) Seat.DEALER else Seat.PONE

    /**
     * A player's cards not yet laid on the pegging pile. The 4-card [hands] stay intact through
     * the whole hand — they're needed for the show — so pegging progress is tracked via
     * [playSequence] rather than by removing cards.
     */
    fun unplayed(of: PlayerID): List<Card> {
        val played = playSequence.filter { it.player == of }.map { it.card }.toSet()
        return (hands[of] ?: emptyList()).filterNot { it in played }
    }

    /** True once every card has been laid during pegging. */
    val allCardsPlayed: Boolean
        get() = unplayed(PlayerID.ONE).isEmpty() && unplayed(PlayerID.TWO).isEmpty()

    /**
     * Builds the redacted view for one device. The opponent's hole cards are only ever included
     * once the phase reveals hands — before that the wire literally never carries them.
     */
    fun snapshot(you: PlayerID): PlayerSnapshot {
        val opponent = you.opponent
        val reveal = phase.revealsHands
        // During pegging a player sees only their still-unplayed cards; at the show, the full 4.
        val yourVisibleHand =
            if (phase == GamePhase.PEGGING) unplayed(you) else (hands[you] ?: emptyList())
        return PlayerSnapshot(
            matchID = matchID,
            you = you,
            phase = phase,
            yourSeat = seat(of = you),
            dealer = dealer,
            yourHand = yourVisibleHand,
            opponentHandCount =
                if (phase == GamePhase.PEGGING) unplayed(opponent).size else (hands[opponent]?.size ?: 0),
            opponentHand = if (reveal) hands[opponent] else null,
            crib = if (phase.revealsCrib) crib else null,
            cribCount = crib.size,
            starter = starter,
            starterCutLifted = starterCutLifted,
            playSequence = playSequence,
            runningCount = runningCount,
            lapCardCount = lapCards.size,
            whoseTurn = whoseTurn,
            lastToPlay = lastToPlay,
            yourScore = scores[you] ?: 0,
            opponentScore = scores[opponent] ?: 0,
            flags = if (scoringMode.showsFlags) activeFlags else emptyList(),
            scoringMode = scoringMode,
            cutForDeal = cutForDeal,
            winner = winner,
            yourName = names[you] ?: "You",
            opponentName = names[opponent] ?: "Opponent",
            yourColorID = colorIDs[you] ?: 0,
            opponentColorID = colorIDs[opponent] ?: 1,
            playersWithClaims = claimHistory.map { it.player }.toSet(),
            claimTick = claimTick,
            lastClaimPlayer = claimHistory.lastOrNull()?.player,
            lastClaimAmount = claimHistory.lastOrNull()?.amount ?: 0,
            pegEventTick = pegEventTick,
            lastPegEvent = lastPegEvent,
            // Only needed for the win-screen replay.
            scoreLog = if (phase == GamePhase.GAME_OVER) claimHistory else emptyList(),
        )
    }

    companion object {
        /**
         * A fresh state for a brand-new match. [dealer] is provisional here; the real dealer is
         * decided by the cut-for-deal phase.
         */
        fun newMatch(
            matchID: String,
            seed: ULong,
            names: Map<PlayerID, String>,
            colorIDs: Map<PlayerID, Int>,
            scoringMode: ScoringMode = ScoringMode.OFF,
        ): GameState = GameState(
            matchID = matchID,
            scoringMode = scoringMode,
            names = names,
            colorIDs = colorIDs,
            dealer = PlayerID.ONE,
            seed = seed,
            deck = Deck.shuffled(seed = seed),
        )
    }
}
