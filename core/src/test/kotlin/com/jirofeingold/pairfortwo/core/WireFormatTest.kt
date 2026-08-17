package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the leaf wire encodings that must match iOS byte-for-byte.
 *
 * These are the exact points where Swift's derived Codable and kotlinx.serialization
 * disagree by default (PLAN.md §0.2), so they are asserted rather than assumed.
 */
class WireFormatTest {

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `card encodes rank as int and suit as lowercase string`() {
        assertEquals(
            """{"rank":1,"suit":"spades"}""",
            json.encodeToString(Card(Rank.ACE, Suit.SPADES)),
        )
        assertEquals(
            """{"rank":13,"suit":"hearts"}""",
            json.encodeToString(Card(Rank.KING, Suit.HEARTS)),
        )
    }

    @Test
    fun `playerID-keyed map encodes as an object, not a flat array`() {
        // Swift's derived Codable emits ["two",5,"one",3] here. The protocol says object.
        val scores = mapOf(PlayerID.ONE to 3, PlayerID.TWO to 5)
        assertEquals(
            """{"one":3,"two":5}""",
            json.encodeToString(MapSerializer(PlayerID.serializer(), Int.serializer()), scores),
        )
    }

    @Test
    fun `playerID set encodes sorted regardless of insertion order`() {
        val a = PlayerSnapshotFixtures.minimal(playersWithClaims = linkedSetOf(PlayerID.TWO, PlayerID.ONE))
        val b = PlayerSnapshotFixtures.minimal(playersWithClaims = linkedSetOf(PlayerID.ONE, PlayerID.TWO))
        assertEquals(json.encodeToString(a), json.encodeToString(b))
        assert(json.encodeToString(a).contains(""""playersWithClaims":["one","two"]"""))
    }

    @Test
    fun `scoringMode encodes as its int raw value`() {
        assertEquals("0", json.encodeToString(ScoringMode.AUTO))
        assertEquals("2", json.encodeToString(ScoringMode.OFF))
    }

    @Test
    fun `gamePhase and seat encode as iOS raw strings`() {
        assertEquals("\"discardToCrib\"", json.encodeToString(GamePhase.DISCARD_TO_CRIB))
        assertEquals("\"cutForDeal\"", json.encodeToString(GamePhase.CUT_FOR_DEAL))
        assertEquals("\"pone\"", json.encodeToString(Seat.PONE))
    }

    @Test
    fun `absent optionals are omitted rather than emitted as null`() {
        val encoded = json.encodeToString(PlayerSnapshotFixtures.minimal())
        assert(!encoded.contains("null")) { "expected no nulls, got: $encoded" }
        assert(!encoded.contains("starter\"")) { "expected absent starter to be omitted" }
    }

    @Test
    fun `crib owners encode as a sorted array of card-player pairs`() {
        // A Card can't be a JSON object key, so PROTOCOL.md pins this to an array sorted by rank
        // then suit — the bytes must not depend on map iteration order. iOS's `WireCodec.cardOwners`
        // is the other half.
        val owners = linkedMapOf(
            Card(Rank.KING, Suit.CLUBS) to PlayerID.TWO,
            Card(Rank.ACE, Suit.HEARTS) to PlayerID.ONE,
            Card(Rank.ACE, Suit.SPADES) to PlayerID.TWO,
        )
        val encoded = PairWireJson.encodeToString(CardOwnerMapSerializer, owners)
        assertEquals(
            """[{"card":{"rank":1,"suit":"spades"},"player":"two"},""" +
                """{"card":{"rank":1,"suit":"hearts"},"player":"one"},""" +
                """{"card":{"rank":13,"suit":"clubs"},"player":"two"}]""",
            encoded,
        )
        assertEquals(owners, PairWireJson.decodeFromString(CardOwnerMapSerializer, encoded))
    }

    @Test
    fun `a lastCard peg event round-trips, as iOS 1_6 sends it`() {
        // Unknown enum values are a hard decode failure, so this case missing meant a snapshot from
        // an iOS 1.6 host took the whole game down rather than degrading.
        val decoded = PairWireJson.decodeFromString(
            PegEvent.serializer(),
            """{"kind":"lastCard","scorer":"one","points":2}""",
        )
        assertEquals(PegEvent.Kind.LAST_CARD, decoded.kind)
        assertEquals("lastCard", decoded.kind.wireName)
    }

    @Test
    fun `snapshot round-trips`() {
        val original = PlayerSnapshotFixtures.minimal()
        assertEquals(original, json.decodeFromString<PlayerSnapshot>(json.encodeToString(original)))
    }
}

/** Shared minimal snapshot so the assertions above stay readable. */
object PlayerSnapshotFixtures {
    fun minimal(playersWithClaims: Set<PlayerID> = emptySet()) = PlayerSnapshot(
        matchID = "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        you = PlayerID.ONE,
        phase = GamePhase.PEGGING,
        yourSeat = Seat.PONE,
        dealer = PlayerID.TWO,
        yourHand = listOf(Card(Rank.FIVE, Suit.CLUBS)),
        opponentHandCount = 4,
        cribCount = 0,
        starterCutLifted = false,
        playSequence = emptyList(),
        runningCount = 0,
        lapCardCount = 0,
        yourScore = 0,
        opponentScore = 0,
        flags = emptyList(),
        scoringMode = ScoringMode.OFF,
        cutForDeal = emptyMap(),
        yourName = "A",
        opponentName = "B",
        yourColorID = 0,
        opponentColorID = 1,
        playersWithClaims = playersWithClaims,
        claimTick = 0,
        lastClaimAmount = 0,
        pegEventTick = 0,
    )
}
