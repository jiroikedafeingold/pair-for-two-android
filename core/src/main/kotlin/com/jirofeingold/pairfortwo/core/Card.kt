package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Port of the iOS app's `Card.swift`.
 *
 * The wire form is `{"rank":1,"suit":"spades"}` — `rank` an Int 1…13, `suit` a lowercase
 * string. That is what Swift's derived `Codable` already produces for these two types, so
 * unlike the rest of the protocol (PLAN.md §0.2) no translation is needed; it just has to be
 * pinned on this side too. See PROTOCOL.md.
 */

// ---- Suit ----

@Serializable
enum class Suit {
    @SerialName("spades") SPADES,
    @SerialName("hearts") HEARTS,
    @SerialName("diamonds") DIAMONDS,
    @SerialName("clubs") CLUBS;

    /** The glyph drawn on a card face. */
    val symbol: String
        get() = when (this) {
            SPADES -> "♠"
            HEARTS -> "♥"
            DIAMONDS -> "♦"
            CLUBS -> "♣"
        }

    /** Hearts and diamonds are drawn in red; spades and clubs in near-black. */
    val isRed: Boolean get() = this == HEARTS || this == DIAMONDS

    /** Order suits appear in a sorted hand. */
    val displayOrder: Int
        get() = when (this) {
            SPADES -> 0
            HEARTS -> 1
            DIAMONDS -> 2
            CLUBS -> 3
        }
}

// ---- Rank ----

/**
 * Ace (1) through King (13).
 *
 * Serialized as its Int value, not its name: Swift's `Rank` is an `Int`-raw-value enum, so the
 * wire carries `1`…`13`. Kotlin enums default to serializing by name, hence the explicit
 * serializer.
 */
@Serializable(with = RankSerializer::class)
enum class Rank(val value: Int) {
    ACE(1), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6), SEVEN(7),
    EIGHT(8), NINE(9), TEN(10), JACK(11), QUEEN(12), KING(13);

    /** Value used for pegging and fifteens: Ace = 1, face cards = 10, others = pip value. */
    val countingValue: Int get() = minOf(value, 10)

    /** Value used for detecting runs: Ace = 1 … King = 13 (faces are distinct). */
    val orderValue: Int get() = value

    /** Short label shown on a card corner ("A", "2" … "10", "J", "Q", "K"). */
    val label: String
        get() = when (this) {
            ACE -> "A"
            JACK -> "J"
            QUEEN -> "Q"
            KING -> "K"
            else -> value.toString()
        }

    /** Spoken form for accessibility, e.g. "Seven". */
    val spokenName: String
        get() = when (this) {
            ACE -> "Ace"; TWO -> "Two"; THREE -> "Three"; FOUR -> "Four"
            FIVE -> "Five"; SIX -> "Six"; SEVEN -> "Seven"; EIGHT -> "Eight"
            NINE -> "Nine"; TEN -> "Ten"; JACK -> "Jack"; QUEEN -> "Queen"
            KING -> "King"
        }

    companion object {
        private val byValue = entries.associateBy(Rank::value)

        fun of(value: Int): Rank =
            byValue[value] ?: throw IllegalArgumentException("rank out of range: $value")
    }
}

internal object RankSerializer : KSerializer<Rank> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Rank", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Rank) = encoder.encodeInt(value.value)
    override fun deserialize(decoder: Decoder): Rank = Rank.of(decoder.decodeInt())
}

// ---- Card ----

/** A single playing card. Unique by rank+suit within a standard deck. */
@Serializable
data class Card(val rank: Rank, val suit: Suit) {

    /** Stable identity, matching iOS's `Card.id` ("1spades"). */
    val id: String get() = "${rank.value}${suit.name.lowercase()}"

    /** Convenience for pegging / fifteen arithmetic. */
    val countingValue: Int get() = rank.countingValue

    /** Convenience for run detection. */
    val orderValue: Int get() = rank.orderValue

    /** e.g. "A♠", "10♥", "K♣" — compact debug/label form. */
    val shortName: String get() = "${rank.label}${suit.symbol}"

    /** Spoken form for accessibility, e.g. "Seven of Hearts". */
    val accessibleName: String
        get() = "${rank.spokenName} of ${suit.name.lowercase().replaceFirstChar(Char::uppercase)}"

    /** Stable key for laying a hand out: by rank (Ace → King), then by suit. */
    val displaySortKey: Int get() = rank.orderValue * 4 + suit.displayOrder
}

/** A copy sorted for display — by rank, then suit. */
fun List<Card>.sortedForDisplay(): List<Card> = sortedBy { it.displaySortKey }
