package com.jirofeingold.pairfortwo.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential test: this scorer must agree with the shipping **iOS** scorer, case for case.
 *
 * The fixtures in `fixtures/scorer-v1/` were emitted by the iOS implementation itself
 * (`tools/generate-scorer-fixtures.sh` in that repo, compiled against the real
 * `CribbageScorer.swift`), so agreement here means the two apps count identically. That matters
 * because the host is the sole referee (PLAN.md §0.3) — if the scorers disagreed, a game would
 * play differently depending on who hosted, which is exactly the kind of bug that surfaces once,
 * mid-game, on someone else's phone.
 *
 * `score` is compared as an **ordered** list, not a set or a total: iOS emits one flag per fifteen
 * and one per pair, and the UI shows them in that order during the show. `detail` is compared too,
 * since the player reads those strings.
 *
 * Fixture format: cards are `<rank 1…13><suit initial>` — `"1s"`, `"10h"`, `"11d"`, `"13c"`.
 * Flags are `ScoreFlag.id`, i.e. `"kind|detail|points"`.
 */
class ScorerFixtureTest {

    private val fixtureDir = File("../fixtures/scorer-v1")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ShowFixture(val version: Int, val cases: List<ShowCase>)

    @Serializable
    private data class ShowCase(
        val hand: List<String>,
        val starter: String,
        val isCrib: Boolean,
        val score: List<String>,
        val breakdown: List<String>,
    )

    @Serializable
    private data class PegFixture(val version: Int, val cases: List<PegCase>)

    @Serializable
    private data class PegCase(val pile: List<String>, val played: String, val score: List<String>)

    // ---- Token parsing ----

    private fun card(token: String): Card {
        val suit = when (token.last()) {
            's' -> Suit.SPADES
            'h' -> Suit.HEARTS
            'd' -> Suit.DIAMONDS
            'c' -> Suit.CLUBS
            else -> throw IllegalArgumentException("bad suit in card token '$token'")
        }
        val rank = Rank.of(token.dropLast(1).toInt())
        return Card(rank, suit)
    }

    private fun cards(tokens: List<String>): List<Card> = tokens.map(::card)

    /**
     * Collects every mismatch rather than failing on the first, then reports the count with a
     * sample. A single failing case usually means one rule is wrong, and seeing several examples
     * of it side by side identifies which rule far faster than one case does.
     */
    private fun assertNoMismatches(total: Int, mismatches: List<String>) {
        assertTrue(
            mismatches.isEmpty(),
            "${mismatches.size} of $total cases disagree with the iOS scorer:\n" +
                mismatches.take(10).joinToString("\n") +
                if (mismatches.size > 10) "\n… and ${mismatches.size - 10} more" else "",
        )
    }

    // ---- Tests ----

    @Test
    fun `fixtures are present and substantial`() {
        assertTrue(fixtureDir.isDirectory, "fixtures not found at ${fixtureDir.absolutePath}")
        val show = json.decodeFromString<ShowFixture>(File(fixtureDir, "show.json").readText())
        val peg = json.decodeFromString<PegFixture>(File(fixtureDir, "pegging.json").readText())
        assertEquals(1, show.version)
        assertEquals(1, peg.version)

        // Guards against a truncated or accidentally-regenerated-empty corpus silently passing.
        assertTrue(show.cases.size > 10_000, "show corpus shrank to ${show.cases.size} cases")
        assertTrue(peg.cases.size > 4_000, "pegging corpus shrank to ${peg.cases.size} cases")
        assertTrue(
            show.cases.count { it.score.isNotEmpty() } > show.cases.size * 9 / 10,
            "most show cases should score something; a corpus of blanks would prove nothing",
        )
    }

    @Test
    fun `handScore matches iOS on every fixture`() {
        val fixture = json.decodeFromString<ShowFixture>(File(fixtureDir, "show.json").readText())
        val mismatches = fixture.cases.mapNotNull { c ->
            val actual = CribbageScorer
                .handScore(cards(c.hand), card(c.starter), c.isCrib)
                .map { it.id }
            if (actual == c.score) null
            else "  ${c.hand}+${c.starter}${if (c.isCrib) " (crib)" else ""}\n" +
                "    expected ${c.score}\n    actual   $actual"
        }
        assertNoMismatches(fixture.cases.size, mismatches)
    }

    @Test
    fun `handBreakdown matches iOS on every fixture`() {
        val fixture = json.decodeFromString<ShowFixture>(File(fixtureDir, "show.json").readText())
        val mismatches = fixture.cases.mapNotNull { c ->
            val actual = CribbageScorer
                .handBreakdown(cards(c.hand), card(c.starter), c.isCrib)
                .map { it.id }
            if (actual == c.breakdown) null
            else "  ${c.hand}+${c.starter}${if (c.isCrib) " (crib)" else ""}\n" +
                "    expected ${c.breakdown}\n    actual   $actual"
        }
        assertNoMismatches(fixture.cases.size, mismatches)
    }

    /**
     * The breakdown is a *presentation* of the same count, so its total must equal `handScore`'s
     * on every case. iOS asserts nothing of the kind — it's an invariant the two functions are
     * meant to share, and this corpus is the cheapest place to hold them to it.
     */
    @Test
    fun `handBreakdown totals the same as handScore`() {
        val fixture = json.decodeFromString<ShowFixture>(File(fixtureDir, "show.json").readText())
        val mismatches = fixture.cases.mapNotNull { c ->
            val hand = cards(c.hand)
            val starter = card(c.starter)
            val score = CribbageScorer.handScore(hand, starter, c.isCrib).totalPoints
            val breakdown = CribbageScorer.handBreakdown(hand, starter, c.isCrib).totalPoints
            if (score == breakdown) null
            else "  ${c.hand}+${c.starter}: handScore $score vs breakdown $breakdown"
        }
        assertNoMismatches(fixture.cases.size, mismatches)
    }

    @Test
    fun `peggingScore matches iOS on every fixture`() {
        val fixture = json.decodeFromString<PegFixture>(File(fixtureDir, "pegging.json").readText())
        val mismatches = fixture.cases.mapNotNull { c ->
            val actual = CribbageScorer
                .peggingScore(cards(c.pile), card(c.played))
                .map { it.id }
            if (actual == c.score) null
            else "  pile ${c.pile} played ${c.played}\n" +
                "    expected ${c.score}\n    actual   $actual"
        }
        assertNoMismatches(fixture.cases.size, mismatches)
    }

    /** The 29-hand — the highest possible — must be in the corpus and must score 29. */
    @Test
    fun `the corpus contains a 29 hand`() {
        val fixture = json.decodeFromString<ShowFixture>(File(fixtureDir, "show.json").readText())
        val best = fixture.cases.maxOf { c ->
            CribbageScorer.handScore(cards(c.hand), card(c.starter), c.isCrib).totalPoints
        }
        assertEquals(29, best, "the best hand in the corpus should be the 29")
    }
}
