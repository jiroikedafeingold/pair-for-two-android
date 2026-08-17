package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Port of the iOS app's `CribbageModels.swift`. */

// ---- Players & seats ----

/** Which of the two devices/players this is. Stable for the whole match. */
@Serializable
enum class PlayerID {
    @SerialName("one") ONE,
    @SerialName("two") TWO;

    val opponent: PlayerID get() = if (this == ONE) TWO else ONE

    /** Wire form — also the key used for `Map<PlayerID, …>` objects. See PROTOCOL.md. */
    val wireName: String get() = if (this == ONE) "one" else "two"
}

/**
 * The role a player holds for a single hand. The dealer owns the crib; the pone leads the
 * pegging. Dealer alternates each hand.
 */
@Serializable
enum class Seat {
    @SerialName("dealer") DEALER,
    @SerialName("pone") PONE;

    val other: Seat get() = if (this == DEALER) PONE else DEALER
}

// ---- Scoring mode ----

/**
 * How the app handles scoring. Chosen by the host; governs the whole game.
 *
 * Serialized as its Int raw value, matching Swift's `ScoringMode: Int`.
 */
@Serializable(with = ScoringModeSerializer::class)
enum class ScoringMode(val value: Int) {
    /** The app counts and adds every score for you. */
    AUTO(0),

    /** The app shows each score and the total; you add them on your slider. */
    FEEDBACK(1),

    /** No hints — you count and add every score yourself. */
    OFF(2);

    val showsFlags: Boolean get() = this != OFF
    val isAuto: Boolean get() = this == AUTO

    /** Settings-screen label, same wording as iOS's `ScoringMode.title`. */
    val title: String
        get() = when (this) {
            AUTO -> "Automatic"
            FEEDBACK -> "Feedback"
            OFF -> "Player responsibility"
        }

    /** The one-line explanation under [title], same wording as iOS's `ScoringMode.detail`. */
    val detail: String
        get() = when (this) {
            AUTO -> "The app counts and adds every score for you."
            FEEDBACK -> "The app shows each score and the total; you add them on your slider."
            OFF -> "No hints — you count and add every score yourself."
        }

    companion object {
        private val byValue = entries.associateBy(ScoringMode::value)

        /** Unknown values fall back to OFF rather than throwing — a forward-compatible peer
         *  might send a mode this build doesn't know about. */
        fun of(value: Int): ScoringMode = byValue[value] ?: OFF
    }
}

internal object ScoringModeSerializer : KSerializer<ScoringMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ScoringMode", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: ScoringMode) = encoder.encodeInt(value.value)
    override fun deserialize(decoder: Decoder): ScoringMode = ScoringMode.of(decoder.decodeInt())
}

// ---- Phases ----

/** The full lifecycle of a hand/game, driven by the host-authoritative engine. */
@Serializable
enum class GamePhase {
    @SerialName("connecting") CONNECTING,
    @SerialName("cutForDeal") CUT_FOR_DEAL,
    @SerialName("dealing") DEALING,
    @SerialName("discardToCrib") DISCARD_TO_CRIB,

    /** Manual two-step cut: the pone lifts the deck, the dealer reveals the starter. */
    @SerialName("cutStarter") CUT_STARTER,

    @SerialName("pegging") PEGGING,
    @SerialName("showPone") SHOW_PONE,
    @SerialName("showDealer") SHOW_DEALER,
    @SerialName("showCrib") SHOW_CRIB,
    @SerialName("handComplete") HAND_COMPLETE,
    @SerialName("gameOver") GAME_OVER;

    /**
     * How far through a hand this phase is. Used to decide what is revealed — comparing ranks
     * is what makes "hands are visible from the pone's show onward" a single expression.
     */
    val revealRank: Int
        get() = when (this) {
            CONNECTING -> 0
            CUT_FOR_DEAL -> 1
            DEALING -> 2
            DISCARD_TO_CRIB -> 3
            CUT_STARTER -> 4
            PEGGING -> 5
            SHOW_PONE -> 6
            SHOW_DEALER -> 7
            SHOW_CRIB -> 8
            HAND_COMPLETE -> 9
            GAME_OVER -> 10
        }

    val revealsHands: Boolean get() = revealRank >= SHOW_PONE.revealRank
    val revealsCrib: Boolean get() = revealRank >= SHOW_CRIB.revealRank

    /**
     * Whether this phase can put scoring flags on the table — his heels at the cut, the pegging
     * scores, each hand and the crib at the show. Drives how much room the action rail keeps for
     * them: reserving the slot in *every* phase pushed the tall "tap to cut" card off the bottom.
     */
    val surfacesScoreFlags: Boolean
        get() = this == CUT_STARTER || this == PEGGING ||
            this == SHOW_PONE || this == SHOW_DEALER || this == SHOW_CRIB

    /** The wire/serial name, matching iOS's raw values. */
    val wireName: String
        get() = when (this) {
            CONNECTING -> "connecting"
            CUT_FOR_DEAL -> "cutForDeal"
            DEALING -> "dealing"
            DISCARD_TO_CRIB -> "discardToCrib"
            CUT_STARTER -> "cutStarter"
            PEGGING -> "pegging"
            SHOW_PONE -> "showPone"
            SHOW_DEALER -> "showDealer"
            SHOW_CRIB -> "showCrib"
            HAND_COMPLETE -> "handComplete"
            GAME_OVER -> "gameOver"
        }
}

// ---- Seeded RNG ----

/**
 * SplitMix64 — a small, deterministic generator, ported exactly from the Swift.
 *
 * Note this does *not* need to produce the same *shuffle* as iOS: the host is the sole
 * referee and ships the resulting deal in its snapshots, so the two platforms never
 * re-derive a shuffle from a shared seed (PLAN.md §0.3). It is ported faithfully anyway so
 * a seed reproduces a deal within a platform, which is what the engine tests rely on.
 */
class SeededGenerator(seed: ULong) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
        return z xor (z shr 31)
    }

    /** Uniform in 0 until [bound], via rejection sampling to avoid modulo bias. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val b = bound.toULong()
        val limit = ULong.MAX_VALUE - (ULong.MAX_VALUE % b) - 1uL
        var r = next()
        while (r > limit) r = next()
        return (r % b).toInt()
    }
}

// ---- Deck ----

/** A standard 52-card deck. */
@Serializable
data class Deck(val cards: List<Card>) {

    companion object {
        /** A fresh, ordered 52-card deck (suit-major, Ace → King), matching iOS. */
        fun ordered(): Deck = Deck(
            Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit) } }
        )

        /** A shuffled deck. With a [seed] the shuffle is deterministic. */
        fun shuffled(seed: ULong? = null): Deck {
            val cards = ordered().cards.toMutableList()
            if (seed == null) {
                cards.shuffle()
            } else {
                // Fisher-Yates, descending — deterministic for a given seed.
                val rng = SeededGenerator(seed)
                for (i in cards.indices.reversed()) {
                    val j = rng.nextInt(i + 1)
                    val tmp = cards[i]; cards[i] = cards[j]; cards[j] = tmp
                }
            }
            return Deck(cards)
        }
    }

    /** The card at a cut index (wraps around the deck length). */
    fun cardAtCut(index: Int): Card = cards[((index % cards.size) + cards.size) % cards.size]

    /** Returns the top [count] cards and the remaining deck. */
    fun deal(count: Int): Pair<List<Card>, Deck> {
        val n = minOf(count, cards.size)
        return cards.take(n) to Deck(cards.drop(n))
    }
}
