package com.jirofeingold.pairfortwo.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Differential test: this referee must reach the same state as the **iOS** referee after every
 * step of every scripted game.
 *
 * The scripts in `fixtures/engine-v1/engine.json` were produced by the iOS implementation
 * (`tools/generate-engine-fixtures.sh` in that repo, compiled against the real
 * `CribbageEngine.swift`). Each script fixes the deck, the hands and the starter explicitly, then
 * issues a sequence of intents; the fixture records the full state after each one.
 *
 * The comparison is per step, not per outcome, and includes each handler's boolean return. The
 * failure mode this is really guarding against isn't a wrong score — it's the two devices
 * disagreeing about whose turn it is, which doesn't show up as a wrong number, it shows up as a
 * game that stops.
 *
 * Anything that reshuffles — `dealNewHand`, the cut-for-deal tie recut, `playAgain` — is out of
 * scope by design: PLAN.md §0.3 accepts that the platforms don't share a shuffle, since only the
 * host deals and it ships the result. Those paths are covered structurally by [EngineTest].
 */
class EngineFixtureTest {

    private val fixtureFile = File("../fixtures/engine-v1/engine.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Fixture(val version: Int, val scripts: List<Script>)

    @Serializable
    private data class Script(
        val name: String,
        val setup: Setup,
        val steps: List<JsonObject>,
        val trace: List<JsonObject>,
    )

    @Serializable
    private data class Setup(
        val dealer: String,
        val phase: String,
        val mode: Int,
        val hand: Int,
        val s1: Int,
        val s2: Int,
        val h1: List<String>,
        val h2: List<String>,
        val crib: List<String>,
        val starter: String? = null,
        val turn: String? = null,
        val deck: List<String>,
    )

    // ---- Token parsing ----

    private fun card(token: String): Card {
        val suit = when (token.last()) {
            's' -> Suit.SPADES
            'h' -> Suit.HEARTS
            'd' -> Suit.DIAMONDS
            'c' -> Suit.CLUBS
            else -> throw IllegalArgumentException("bad suit in card token '$token'")
        }
        return Card(Rank.of(token.dropLast(1).toInt()), suit)
    }

    private fun cards(tokens: List<String>) = tokens.map(::card)

    private fun player(raw: String) = if (raw == "one") PlayerID.ONE else PlayerID.TWO

    private fun phase(raw: String) = GamePhase.entries.first { it.wireName == raw }

    // ---- Replay ----

    private fun buildState(setup: Setup) = GameState(
        matchID = "6BA7B810-9DAD-11D1-80B4-00C04FD430C8",
        phase = phase(setup.phase),
        handNumber = setup.hand,
        scoringMode = ScoringMode.of(setup.mode),
        names = mapOf(PlayerID.ONE to "Jiro", PlayerID.TWO to "Sam"),
        colorIDs = mapOf(PlayerID.ONE to 2, PlayerID.TWO to 7),
        scores = mapOf(PlayerID.ONE to setup.s1, PlayerID.TWO to setup.s2),
        dealer = player(setup.dealer),
        seed = 0x5EEDuL,
        deck = Deck(cards(setup.deck)),
        hands = mapOf(PlayerID.ONE to cards(setup.h1), PlayerID.TWO to cards(setup.h2)),
        crib = cards(setup.crib),
        starter = setup.starter?.let(::card),
        whoseTurn = setup.turn?.let(::player),
    )

    /** `begin` returns Unit in both implementations, so it reports `true`, as the emitter does. */
    private fun applyStep(s: GameState, step: JsonObject): Boolean {
        fun str(key: String) = step[key]!!.toString().trim('"')
        fun int(key: String) = step[key]!!.toString().toInt()
        fun list(key: String) = json.decodeFromJsonElement<List<String>>(step[key]!!)

        return when (val op = str("do")) {
            "begin" -> { CribbageEngine.begin(s); true }
            "cut" -> CribbageEngine.cutForDeal(s, player(str("p")), int("i"))
            "discard" -> CribbageEngine.discard(s, player(str("p")), cards(list("cards")))
            "lift" -> CribbageEngine.liftStarterCut(s, player(str("p")), int("i"))
            "reveal" -> CribbageEngine.revealStarter(s, player(str("p")))
            "play" -> CribbageEngine.play(s, player(str("p")), card(str("card")))
            "go" -> CribbageEngine.go(s, player(str("p")))
            "claim" -> CribbageEngine.claim(s, player(str("p")), int("n"))
            "undo" -> CribbageEngine.undo(s, player(str("p")))
            "advance" -> CribbageEngine.advance(s)
            else -> throw IllegalArgumentException("unknown step '$op'")
        }
    }

    /**
     * The same digest the emitter writes, rebuilt from Kotlin state. Comparing digests rather than
     * field-by-field assertions means a new field added to the emitter fails loudly here instead of
     * being silently skipped.
     */
    private fun digest(s: GameState, ok: Boolean): Map<String, String> {
        fun toks(cs: List<Card>) = cs.joinToString(",") { tk(it) }
        fun plain(cs: List<Card>) = if (cs.isEmpty()) "" else cs.joinToString("+") { tk(it) }

        fun snap(you: PlayerID): String {
            val v = s.snapshot(you)
            return "${plain(v.yourHand)}:${v.opponentHandCount}:" +
                "${v.opponentHand?.let(::plain) ?: "-"}:${v.crib?.let(::plain) ?: "-"}:" +
                "${v.flags.size}:${v.scoreLog.size}:${v.lapCardCount}"
        }

        return mapOf(
            "ok" to ok.toString(),
            "phase" to s.phase.wireName,
            "hand" to s.handNumber.toString(),
            "dealer" to s.dealer.wireName,
            "turn" to (s.whoseTurn?.wireName ?: "null"),
            "last" to (s.lastToPlay?.wireName ?: "null"),
            "s1" to (s.scores[PlayerID.ONE] ?: 0).toString(),
            "s2" to (s.scores[PlayerID.TWO] ?: 0).toString(),
            "h1" to toks(s.hands[PlayerID.ONE] ?: emptyList()),
            "h2" to toks(s.hands[PlayerID.TWO] ?: emptyList()),
            "u1" to toks(s.unplayed(PlayerID.ONE)),
            "u2" to toks(s.unplayed(PlayerID.TWO)),
            "lap" to toks(s.lapCards),
            "count" to s.runningCount.toString(),
            "seq" to s.playSequence.joinToString(",") { "${tk(it.card)}:${it.player.wireName}" },
            "go" to s.goPlayers.map { it.wireName }.sorted().joinToString(","),
            "flags" to s.activeFlags.joinToString(",") { it.id },
            "crib" to toks(s.crib),
            "starter" to (s.starter?.let(::tk) ?: "null"),
            "lifted" to s.starterCutLifted.toString(),
            "cutIdx" to (s.starterCutIndex?.toString() ?: "null"),
            "disc" to s.discarded.map { it.wireName }.sorted().joinToString(","),
            "cut1" to (s.cutForDeal[PlayerID.ONE]?.let(::tk) ?: "null"),
            "cut2" to (s.cutForDeal[PlayerID.TWO]?.let(::tk) ?: "null"),
            "ct" to s.claimTick.toString(),
            "claims" to s.claimHistory.joinToString(",") {
                "${it.player.wireName}:${it.amount}:${it.phase.wireName}"
            },
            "pt" to s.pegEventTick.toString(),
            "pe" to (s.lastPegEvent?.let { "${it.kind.wireName}:${it.scorer.wireName}:${it.points}" } ?: "null"),
            "win" to (s.winner?.wireName ?: "null"),
            "allPlayed" to s.allCardsPlayed.toString(),
            "snap1" to snap(PlayerID.ONE),
            "snap2" to snap(PlayerID.TWO),
        )
    }

    /** The fixture's digest, flattened to the same shape: JSON arrays become comma-joined strings. */
    private fun expected(node: JsonObject): Map<String, String> =
        node.mapValues { (_, v) ->
            val raw = v.toString()
            when {
                raw.startsWith("[") ->
                    json.decodeFromJsonElement<List<String>>(v).joinToString(",")
                else -> raw.trim('"')
            }
        }

    private fun fixture(): Fixture {
        assertTrue(fixtureFile.isFile, "fixtures not found at ${fixtureFile.absolutePath}")
        return json.decodeFromString(fixtureFile.readText())
    }

    // ---- Tests ----

    /**
     * One test per script, so a failure names the game that diverged. The message reports the
     * first differing step and only the keys that differ — a 32-field digest dumped whole is
     * unreadable, and the first divergence is the one that caused all the rest.
     */
    @TestFactory
    fun `every scripted game replays identically`(): List<DynamicTest> = fixture().scripts.map { script ->
        DynamicTest.dynamicTest(script.name) {
            val s = buildState(script.setup)
            script.steps.forEachIndexed { i, step ->
                val ok = applyStep(s, step)
                val actual = digest(s, ok)
                val want = expected(script.trace[i])
                val differing = want.keys.filter { actual[it] != want[it] }
                assertTrue(
                    differing.isEmpty(),
                    "step $i (${step["do"]}) of '${script.name}' diverged:\n" +
                        differing.joinToString("\n") {
                            "  $it: expected '${want[it]}' but was '${actual[it]}'"
                        },
                )
            }
        }
    }

    @Test
    fun `the corpus exercises rejection and the win path`() {
        val f = fixture()
        assertEquals(1, f.version)
        assertTrue(f.scripts.size >= 100, "corpus shrank to ${f.scripts.size} scripts")

        val steps = f.scripts.sumOf { it.steps.size }
        val refused = f.scripts.sumOf { s -> s.trace.count { it["ok"].toString() == "false" } }
        val wins = f.scripts.count { s -> s.trace.lastOrNull()?.get("win")?.toString() != "null" }

        // A corpus of only-legal intents would pass on both sides while testing half the engine.
        assertTrue(refused > steps / 20, "only $refused of $steps steps were illegal intents")
        assertTrue(wins >= 5, "only $wins scripts reach a win")
    }

    /** Every digest key the emitter writes must be one this test actually compares. */
    @Test
    fun `no fixture field goes unchecked`() {
        val f = fixture()
        val fixtureKeys = f.scripts.first().trace.first().keys
        val script = f.scripts.first()
        val s = buildState(script.setup)
        val ourKeys = digest(s, true).keys
        assertEquals(
            emptySet<String>(),
            fixtureKeys - ourKeys,
            "the iOS emitter records fields this test never compares",
        )
    }
}

/** Fixture card token: rank raw value plus a suit initial — "1s", "10h", "11d", "13c". */
private fun tk(c: Card): String {
    val suit = when (c.suit) {
        Suit.SPADES -> "s"
        Suit.HEARTS -> "h"
        Suit.DIAMONDS -> "d"
        Suit.CLUBS -> "c"
    }
    return "${c.rank.value}$suit"
}
