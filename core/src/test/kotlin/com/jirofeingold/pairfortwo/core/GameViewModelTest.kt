package com.jirofeingold.pairfortwo.core

import com.jirofeingold.pairfortwo.core.net.GameTransport
import com.jirofeingold.pairfortwo.core.net.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * [GameViewModel] driven as a real player would drive it.
 *
 * The point of keeping the view model in `:core` rather than `:app` is right here: a complete game —
 * cut for deal, discards, starter cut, pegging, three shows, next hand, all the way to 121 — plays
 * out in a JVM test in milliseconds. On the UI side that would need an instrumented test and an
 * emulator, and nobody would run it often enough to matter.
 */
@Timeout(30)
class GameViewModelTest {

    private val scopes = mutableListOf<CoroutineScope>()

    /** Unconfined so every `launch` runs eagerly and the tests stay deterministic. */
    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Unconfined + SupervisorJob()).also { scopes += it }

    @AfterEach
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun loopback(mode: ScoringMode = ScoringMode.AUTO) = GameViewModel.loopback(
        names = mapOf(PlayerID.ONE to "Ada", PlayerID.TWO to "Bo"),
        colorIDs = mapOf(PlayerID.ONE to 2, PlayerID.TWO to 7),
        scope = newScope(),
        seed = 0x5EEDuL,
        scoringMode = mode,
    )

    // ---- A whole game, played through the view model's own intents ----

    /**
     * Plays like a person would: only ever calling what the UI would call, and only when the view
     * model says it is available. If a derived helper is wrong — `youLiftCut` never true, say — this
     * stalls and fails rather than quietly taking a shortcut the UI couldn't.
     */
    private fun playUntil(vm: GameViewModel, maxSteps: Int = 4000, stop: () -> Boolean): Int {
        var steps = 0
        while (!stop() && steps < maxSteps) {
            steps += 1
            val s = vm.snapshot.value
            when (s.phase) {
                GamePhase.CUT_FOR_DEAL -> if (vm.cutForDealDecided) vm.advance() else vm.cut()
                GamePhase.DISCARD_TO_CRIB -> {
                    val hand = s.yourHand
                    vm.toggleDiscard(hand[0])
                    vm.toggleDiscard(hand[1])
                    vm.confirmDiscard()
                }
                GamePhase.CUT_STARTER -> if (vm.starterCutLifted) vm.revealStarter() else vm.liftCut()
                GamePhase.PEGGING -> when {
                    vm.peggingComplete -> vm.advance()
                    vm.canSayGo -> vm.sayGo()
                    else -> {
                        val playable = s.yourHand.firstOrNull { vm.isLegalPlay(it) }
                        if (playable != null) vm.play(playable) else vm.sayGo()
                    }
                }
                GamePhase.SHOW_PONE, GamePhase.SHOW_DEALER, GamePhase.SHOW_CRIB -> vm.advance()
                GamePhase.HAND_COMPLETE -> vm.advance()
                GamePhase.GAME_OVER, GamePhase.CONNECTING, GamePhase.DEALING -> return steps
            }
        }
        return steps
    }

    @Test
    fun `a full pass-and-play game reaches a winner`() = runBlocking {
        val vm = loopback(ScoringMode.AUTO)

        assertEquals(GamePhase.CUT_FOR_DEAL, vm.snapshot.value.phase)
        val steps = playUntil(vm) { vm.isGameOver }

        assertTrue(vm.isGameOver, "game did not finish in $steps steps (phase ${vm.snapshot.value.phase})")
        val (winner, skunk) = vm.winnerInfo!!
        assertTrue(vm.score(winner) >= 121, "winner should be at 121+, was ${vm.score(winner)}")
        assertTrue(vm.score(winner.opponent) < 121)
        assertEquals(SkunkLevel.of(vm.score(winner.opponent)), skunk)
        assertTrue(vm.coachBanner.endsWith("wins!"), "banner was '${vm.coachBanner}'")
    }

    @Test
    fun `every phase of a hand is visited on the way`() = runBlocking {
        val vm = loopback(ScoringMode.AUTO)
        val seen = mutableSetOf<GamePhase>()
        var steps = 0
        while (!vm.isGameOver && steps < 4000) {
            seen += vm.snapshot.value.phase
            steps += 1
            playUntil(vm, maxSteps = 1) { false }
        }
        // Dealing is a phase iOS declares but never enters; everything else must be reached.
        assertEquals(
            setOf(
                GamePhase.CUT_FOR_DEAL, GamePhase.DISCARD_TO_CRIB, GamePhase.CUT_STARTER,
                GamePhase.PEGGING, GamePhase.SHOW_PONE, GamePhase.SHOW_DEALER,
                GamePhase.SHOW_CRIB, GamePhase.HAND_COMPLETE,
            ),
            seen,
        )
    }

    // ---- Pass-and-play viewer rotation ----

    @Test
    fun `the viewer rotates to whoever must act`() = runBlocking {
        val vm = loopback(ScoringMode.OFF)

        // Cut for deal: player one cuts first, then player two.
        assertEquals(PlayerID.ONE, vm.viewer)
        vm.cut()
        assertEquals(PlayerID.TWO, vm.viewer)
        vm.cut()
        assertTrue(vm.cutForDealDecided)
        // With the result on show, the device renders the dealer, who deals.
        assertEquals(vm.snapshot.value.dealer, vm.viewer)

        vm.advance()
        assertEquals(GamePhase.DISCARD_TO_CRIB, vm.snapshot.value.phase)
        assertEquals(PlayerID.ONE, vm.viewer, "the first player discards first")

        val first = vm.snapshot.value.yourHand
        vm.toggleDiscard(first[0]); vm.toggleDiscard(first[1]); vm.confirmDiscard()
        assertEquals(PlayerID.TWO, vm.viewer, "then it passes to the other player")
    }

    @Test
    fun `a pending discard selection is cleared when the view passes to the other player`() = runBlocking {
        val vm = loopback(ScoringMode.OFF)
        vm.cut(); vm.cut(); vm.advance()

        val hand = vm.snapshot.value.yourHand
        vm.toggleDiscard(hand[0])
        assertEquals(1, vm.selectedForDiscard.value.size)
        vm.toggleDiscard(hand[1])
        vm.confirmDiscard()

        // The next player must not inherit the previous one's highlighted cards.
        assertTrue(vm.selectedForDiscard.value.isEmpty())
    }

    @Test
    fun `at most two cards can be selected for the crib`() = runBlocking {
        val vm = loopback(ScoringMode.OFF)
        vm.cut(); vm.cut(); vm.advance()
        val hand = vm.snapshot.value.yourHand

        vm.toggleDiscard(hand[0])
        vm.toggleDiscard(hand[1])
        vm.toggleDiscard(hand[2])
        assertEquals(setOf(hand[0], hand[1]), vm.selectedForDiscard.value, "a third pick is ignored")
        assertTrue(vm.canConfirmDiscard)

        vm.toggleDiscard(hand[0])   // tapping again deselects
        assertEquals(setOf(hand[1]), vm.selectedForDiscard.value)
        assertFalse(vm.canConfirmDiscard)
    }

    // ---- Coach banner ----

    @Test
    fun `the coach banner names players rather than seats`() = runBlocking {
        val vm = loopback(ScoringMode.OFF)
        assertEquals("Ada, cut for deal", vm.coachBanner)

        vm.cut(); vm.cut()
        assertTrue(vm.coachBanner.contains("wins the cut"), vm.coachBanner)
        // Never the jargon.
        assertFalse(vm.coachBanner.contains("pone"))
        assertFalse(vm.coachBanner.contains("dealer,"))

        vm.advance()
        assertTrue(vm.coachBanner.contains("discard 2 to"), vm.coachBanner)
    }

    // ---- Host and guest over a real pair of transports ----

    /** Two transports wired to each other, so a host and a guest view model can play. */
    private class PairedTransport(override val isHost: Boolean) : GameTransport {
        private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        override val events: Flow<TransportEvent> = channel.receiveAsFlow()
        var peer: PairedTransport? = null

        override suspend fun send(message: GameMessage) {
            peer?.channel?.trySend(TransportEvent.Received(message))
        }

        override fun reconnect(force: Boolean) = Unit

        fun connect() = channel.trySend(TransportEvent.Connected)
    }

    private fun pair(): Triple<GameViewModel, GameViewModel, () -> Unit> {
        val hostTransport = PairedTransport(isHost = true)
        val guestTransport = PairedTransport(isHost = false)
        hostTransport.peer = guestTransport
        guestTransport.peer = hostTransport

        val host = GameViewModel.networked(
            transport = hostTransport, localName = "Ada", localColorID = 2,
            scope = newScope(), scoringMode = ScoringMode.AUTO, seed = 0x5EEDuL,
        )
        val guest = GameViewModel.networked(
            transport = guestTransport, localName = "Bo", localColorID = 7,
            scope = newScope(),
        )
        return Triple(host, guest) { hostTransport.connect(); guestTransport.connect() }
    }

    @Test
    fun `the guest's hello starts the game and it is seated as player two`() = runBlocking {
        val (host, guest, connect) = pair()

        assertEquals(GamePhase.CONNECTING, host.snapshot.value.phase)
        assertEquals("Waiting for a player to join…", host.coachBanner)
        assertEquals("Connecting…", guest.coachBanner)

        connect()

        assertEquals(ConnectionState.CONNECTED, host.connection.value)
        assertEquals(GamePhase.CUT_FOR_DEAL, host.snapshot.value.phase)
        assertEquals(GamePhase.CUT_FOR_DEAL, guest.snapshot.value.phase, "the guest got a snapshot")
        assertEquals(PlayerID.TWO, guest.snapshot.value.you, "the guest is seated as player two")
        assertEquals(PlayerID.ONE, host.snapshot.value.you)

        // Each device sees the other's name and colour.
        assertEquals("Bo", host.snapshot.value.opponentName)
        assertEquals("Ada", guest.snapshot.value.opponentName)
        assertEquals(7, host.snapshot.value.opponentColorID)
    }

    @Test
    fun `a guest intent is applied by the host and comes back as a snapshot`() = runBlocking {
        val (host, guest, connect) = pair()
        connect()

        assertNull(guest.snapshot.value.cutForDeal[PlayerID.TWO], "nobody has cut yet")
        guest.cut()

        assertNotNull(host.snapshot.value.cutForDeal[PlayerID.TWO], "the host applied the guest's cut")
        assertNotNull(guest.snapshot.value.cutForDeal[PlayerID.TWO], "and told the guest about it")
        assertTrue(guest.waitingForOpponentCut)
        assertTrue(host.youNeedToCut)
    }

    @Test
    fun `a networked guest never sees the opponent's hand before the show`() = runBlocking {
        val (host, guest, connect) = pair()
        connect()
        host.cut(); guest.cut()
        host.advance()

        assertEquals(GamePhase.DISCARD_TO_CRIB, guest.snapshot.value.phase)
        assertEquals(6, guest.snapshot.value.yourHand.size)
        assertNull(guest.snapshot.value.opponentHand, "the wire must not carry the other hand yet")
        assertEquals(6, guest.snapshot.value.opponentHandCount, "only the count")
    }

    @Test
    fun `intents are ignored while a networked game is disconnected`() = runBlocking {
        val (host, _, connect) = pair()
        connect()

        // Nobody has cut yet, so a cut that *did* get through would be plainly visible. An earlier
        // version of this test cut first and then re-cut after disconnecting, which proved nothing:
        // the engine rejects a second cut from the same player regardless of the connection.
        assertTrue(host.snapshot.value.cutForDeal.isEmpty())

        // Simulate the link dropping — the UI may still be showing its buttons.
        host.handleForTest(TransportEvent.Disconnected)
        host.cut()

        assertTrue(
            host.snapshot.value.cutForDeal.isEmpty(),
            "an intent sent while disconnected must not be applied",
        )

        // And it works again once the link is back.
        host.handleForTest(TransportEvent.Connected)
        host.cut()
        assertEquals(1, host.snapshot.value.cutForDeal.size)
    }

    @Test
    fun `a networked device can only score its own peg`() = runBlocking {
        val (host, guest, connect) = pair()
        connect()

        // The guest asks for points on *player one's* peg — the host's. A malformed or malicious
        // client can send exactly this, and the host must bank it against the sender instead.
        guest.claim(amount = 5, player = PlayerID.ONE)

        assertEquals(0, host.score(PlayerID.ONE), "the host's own peg must be untouched")
        assertEquals(5, host.score(PlayerID.TWO), "the points belong to the guest that asked")
        assertEquals(5, guest.snapshot.value.yourScore)
    }

    @Test
    fun `pass-and-play may score either peg, because both players are here`() = runBlocking {
        val vm = loopback(ScoringMode.OFF)
        vm.claim(amount = 4, player = PlayerID.TWO)
        assertEquals(4, vm.score(PlayerID.TWO))
        assertEquals(0, vm.score(PlayerID.ONE))
    }

    @Test
    fun `quitting ends the game on both devices`() = runBlocking {
        val (host, guest, connect) = pair()
        connect()

        assertFalse(host.ended.value)
        assertFalse(guest.ended.value)

        guest.quit()

        // The *other* device ends first. quit() sends the message and then waits out a short flush
        // before ending locally, so the peer can't be left in a game the quitter has already torn
        // down — which is the whole reason for the delay.
        assertTrue(host.ended.value, "the other device must end as soon as the message lands")

        withTimeout(5_000) { while (!guest.ended.value) delay(20) }
        assertTrue(guest.ended.value, "the quitter ends once the message has flushed")
    }

    // ---- Loopback specifics ----

    @Test
    fun `pass-and-play scores either peg, networked only your own`() = runBlocking {
        val loop = loopback(ScoringMode.OFF)
        assertEquals(listOf(PlayerID.ONE, PlayerID.TWO), loop.scorablePlayers)

        val (host, _, connect) = pair()
        connect()
        assertEquals(listOf(PlayerID.ONE), host.scorablePlayers)
    }
}

/**
 * Feeds a transport event straight in, so a test can simulate a drop without a real socket.
 * Test-only, and named so it can't be mistaken for production API.
 */
internal fun GameViewModel.handleForTest(event: TransportEvent) {
    val method = GameViewModel::class.java.getDeclaredMethod("handle", TransportEvent::class.java)
    method.isAccessible = true
    method.invoke(this, event)
}
