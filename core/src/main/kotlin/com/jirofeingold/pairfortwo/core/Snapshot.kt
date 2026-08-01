package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The redacted, per-device view of the game — port of the iOS `PlayerSnapshot` and its
 * companions in `GameState.swift`.
 *
 * This is the only game state that crosses the wire: the host holds the authoritative
 * `GameState` and broadcasts one of these to each device. Your hand is full; the opponent's
 * is a count only, until a reveal phase.
 */

// ---- Played card ----

/** A card laid on the shared pegging pile, tagged with who played it. Visible to both. */
@Serializable
data class PlayedCard(val card: Card, val player: PlayerID) {
    val id: String get() = "${card.id}-${player.wireName}"
}

// ---- Claim (manual scoring) ----

/** A manual score entry (from the slider). Recorded so it can be undone. */
@Serializable
data class Claim(val player: PlayerID, val amount: Int, val phase: GamePhase)

// ---- Pegging event (go / 31 notification) ----

/**
 * A notable pegging moment the *other* device should be nudged about, so the player who
 * earns the point knows to take it. Carried with a monotonically-increasing tick so a
 * repeat (heartbeat) broadcast never re-fires the alert.
 */
@Serializable
data class PegEvent(val kind: Kind, val scorer: PlayerID, val points: Int) {
    @Serializable
    enum class Kind {
        @SerialName("go") GO,
        @SerialName("thirtyOne") THIRTY_ONE;

        /** The wire/serial name, matching iOS's raw values. */
        val wireName: String get() = if (this == GO) "go" else "thirtyOne"
    }
}

// ---- Sorted player set ----

/**
 * `Set<PlayerID>` on the wire is a JSON array, and an array's order is part of the bytes.
 * Golden-fixture tests compare bytes, so the order must not depend on insertion order —
 * this serializer sorts on the way out. See PROTOCOL.md.
 */
internal object SortedPlayerIdSetSerializer : KSerializer<Set<PlayerID>> {
    private val delegate = ListSerializer(PlayerID.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Set<PlayerID>) =
        delegate.serialize(encoder, value.sortedBy { it.ordinal })

    override fun deserialize(decoder: Decoder): Set<PlayerID> =
        delegate.deserialize(decoder).toSet()
}

// ---- Snapshot ----

@Serializable
data class PlayerSnapshot(
    val matchID: String,
    val you: PlayerID,
    val phase: GamePhase,
    val yourSeat: Seat,
    val dealer: PlayerID,

    val yourHand: List<Card>,
    val opponentHandCount: Int,
    /** Non-null only at the show. */
    val opponentHand: List<Card>? = null,
    /** Non-null only once counting reaches the crib. */
    val crib: List<Card>? = null,
    val cribCount: Int,
    val starter: Card? = null,
    /**
     * During the manual starter cut, true once the pone has lifted the deck, so both devices
     * can show the lifted portion set aside awaiting the dealer's reveal.
     */
    val starterCutLifted: Boolean,

    val playSequence: List<PlayedCard>,
    val runningCount: Int,
    /**
     * How many trailing [playSequence] cards belong to the current count (lap). Earlier cards
     * were played in a prior lap — count already reset via a go or 31 — and are shown greyed.
     */
    val lapCardCount: Int,
    val whoseTurn: PlayerID? = null,
    val lastToPlay: PlayerID? = null,

    val yourScore: Int,
    val opponentScore: Int,

    val flags: List<ScoreFlag>,
    val scoringMode: ScoringMode,
    val cutForDeal: Map<PlayerID, Card>,
    val winner: PlayerID? = null,

    val yourName: String,
    val opponentName: String,
    val yourColorID: Int,
    val opponentColorID: Int,

    /**
     * Players that currently have at least one undoable claim, so the guest — which holds no
     * authoritative state — can still enable/disable its undo button.
     */
    @Serializable(with = SortedPlayerIdSetSerializer::class)
    val playersWithClaims: Set<PlayerID>,

    /**
     * Increments on each claim; the most recent claim's player and amount, so the *other*
     * device can preview "+X" next to that player's score before it lands.
     */
    val claimTick: Int,
    val lastClaimPlayer: PlayerID? = null,
    val lastClaimAmount: Int,

    val pegEventTick: Int,
    val lastPegEvent: PegEvent? = null,

    /**
     * The ordered scoring history for the whole game. Only populated at [GamePhase.GAME_OVER],
     * so the win screen can replay how the scores were built up.
     */
    val scoreLog: List<Claim> = emptyList(),
) {
    /** True when it is this device's turn to act during pegging. */
    val isYourTurn: Boolean get() = whoseTurn == you

    /** Whether the pone (non-dealer) leads — used by coach banners. */
    val pone: PlayerID get() = dealer.opponent
}
