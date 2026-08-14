package com.jirofeingold.pairfortwo.core

import com.jirofeingold.pairfortwo.core.net.GameTransport
import com.jirofeingold.pairfortwo.core.net.LoopbackTransport
import com.jirofeingold.pairfortwo.core.net.TransportEvent
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Connection status surfaced to the UI — drives the "Reconnecting…" banner. */
enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

/**
 * Bridges the engine and transport to the UI — port of the iOS `GameViewModel`.
 *
 * Lives in `:core` and imports no Compose, which is the Android equivalent of the project rule that
 * a view model never imports SwiftUI. Keeping it here also means a whole game can be played out in
 * a JVM test in milliseconds, which is how `GameViewModelTest` works.
 *
 * Three roles behind one type, exactly as on iOS:
 * - **Loopback host** (pass-and-play): owns the state, applies intents for the rotating [viewer],
 *   and renders whoever is at the table.
 * - **Networked host**: owns the state, is a fixed player, applies its own and the guest's intents,
 *   and broadcasts the guest's redacted snapshot.
 * - **Guest**: holds no state; sends intents and renders the snapshots it receives.
 *
 * State the UI observes is exposed as [StateFlow]s rather than iOS's `@Observable` properties.
 */
class GameViewModel private constructor(
    private val transport: GameTransport,
    val isLoopback: Boolean,
    private var localName: String,
    private var localColorID: Int,
    private val seed: ULong,
    private var scoringMode: ScoringMode,
    initialState: GameState?,
    initialSnapshot: PlayerSnapshot,
    initialConnection: ConnectionState,
    /**
     * Whether this game may be saved for a relaunch "Rejoin". True for nearby games. iOS sets it
     * false for Game Center, which Android has no equivalent of (PLAN.md §0) — kept so the two
     * view models read alike and so a future relay transport has the hook.
     */
    private val resumable: Boolean,
    private val persistence: GamePersistence,
    private val scope: CoroutineScope,
) {

    private val _snapshot = MutableStateFlow(initialSnapshot)
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _selectedForDiscard = MutableStateFlow<Set<Card>>(emptySet())
    val selectedForDiscard: StateFlow<Set<Card>> = _selectedForDiscard.asStateFlow()

    private val _connection = MutableStateFlow(initialConnection)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    /** Flips true when the game has been quit, locally or by the other player. */
    private val _ended = MutableStateFlow(false)
    val ended: StateFlow<Boolean> = _ended.asStateFlow()

    /**
     * iOS surfaces this when a Game Center opponent drops, since a real-time match can't be
     * rejoined. Android has no online play in v1, so nothing sets it — kept for parity.
     */
    private val _opponentLeft = MutableStateFlow(false)
    val opponentLeft: StateFlow<Boolean> = _opponentLeft.asStateFlow()

    val isHost: Boolean = transport.isHost

    /** Host-only authoritative state; null on a guest, and on a host until the guest joins. */
    private var state: GameState? = initialState

    /** The player this device controls when networked. Loopback uses the rotating [viewer]. */
    private var fixedPlayer: PlayerID = if (transport.isHost) PlayerID.ONE else PlayerID.TWO

    private var cutCounter = 17
    private var lastViewer: PlayerID? = if (initialState != null) PlayerID.ONE else null
    private var eventsJob: Job? = null
    private var heartbeatJob: Job? = null

    init {
        listen()
        // A resuming host has state pre-loaded, so the heartbeat starts now rather than when the
        // guest joins. The guest resyncs on reconnect.
        if (isHost && initialState != null && !isLoopback) startHeartbeat()
    }

    companion object {
        private const val HEARTBEAT_MS = 2_000L
        private const val HELLO_ATTEMPTS = 6
        private const val QUIT_FLUSH_MS = 400L

        /** Single-device pass-and-play. The host owns state immediately for both players. */
        fun loopback(
            names: Map<PlayerID, String>,
            colorIDs: Map<PlayerID, Int>,
            scope: CoroutineScope,
            seed: ULong = Random.nextLong().toULong(),
            scoringMode: ScoringMode = ScoringMode.FEEDBACK,
            persistence: GamePersistence = GamePersistence.None,
            matchID: String = randomMatchID(),
        ): GameViewModel {
            val state = GameState.newMatch(matchID, seed, names, colorIDs, scoringMode)
            CribbageEngine.begin(state)
            return GameViewModel(
                transport = LoopbackTransport(),
                isLoopback = true,
                localName = names[PlayerID.ONE] ?: "Player 1",
                localColorID = colorIDs[PlayerID.ONE] ?: 1,
                seed = seed,
                scoringMode = scoringMode,
                initialState = state,
                initialSnapshot = state.snapshot(PlayerID.ONE),
                initialConnection = ConnectionState.CONNECTED,
                resumable = true,
                persistence = persistence,
                scope = scope,
            )
        }

        /**
         * Two-device play over a real transport. The host builds state once the guest's hello
         * arrives; the guest renders incoming snapshots. The host's [scoringMode] governs.
         */
        fun networked(
            transport: GameTransport,
            localName: String,
            localColorID: Int,
            scope: CoroutineScope,
            scoringMode: ScoringMode = ScoringMode.OFF,
            resumable: Boolean = true,
            seed: ULong = Random.nextLong().toULong(),
            persistence: GamePersistence = GamePersistence.None,
        ): GameViewModel {
            val you = if (transport.isHost) PlayerID.ONE else PlayerID.TWO
            return GameViewModel(
                transport = transport,
                isLoopback = false,
                localName = localName,
                localColorID = localColorID,
                seed = seed,
                scoringMode = scoringMode,
                initialState = null,
                initialSnapshot = placeholderSnapshot(you, localName, localColorID),
                initialConnection = ConnectionState.CONNECTING,
                resumable = resumable,
                persistence = persistence,
                scope = scope,
            )
        }

        /** Resume a saved game as the host; the other player rejoins normally and is resynced. */
        fun resumeHost(
            transport: GameTransport,
            savedState: GameState,
            scope: CoroutineScope,
            persistence: GamePersistence = GamePersistence.None,
        ): GameViewModel = GameViewModel(
            transport = transport,
            isLoopback = false,
            localName = savedState.names[PlayerID.ONE] ?: "Player 1",
            localColorID = savedState.colorIDs[PlayerID.ONE] ?: 1,
            seed = savedState.seed,
            scoringMode = savedState.scoringMode,
            initialState = savedState,
            initialSnapshot = savedState.snapshot(PlayerID.ONE),
            initialConnection = ConnectionState.CONNECTING,
            resumable = true,
            persistence = persistence,
            scope = scope,
        )

        fun placeholderSnapshot(you: PlayerID, name: String, colorID: Int) = PlayerSnapshot(
            matchID = randomMatchID(),
            you = you,
            phase = GamePhase.CONNECTING,
            yourSeat = Seat.PONE,
            dealer = PlayerID.ONE,
            yourHand = emptyList(),
            opponentHandCount = 0,
            opponentHand = null,
            crib = null,
            cribCount = 0,
            starter = null,
            starterCutLifted = false,
            playSequence = emptyList(),
            runningCount = 0,
            lapCardCount = 0,
            whoseTurn = null,
            lastToPlay = null,
            yourScore = 0,
            opponentScore = 0,
            flags = emptyList(),
            scoringMode = ScoringMode.FEEDBACK,
            cutForDeal = emptyMap(),
            winner = null,
            yourName = name,
            opponentName = "Opponent",
            yourColorID = colorID,
            opponentColorID = if (you == PlayerID.ONE) 7 else 1,
            playersWithClaims = emptySet(),
            claimTick = 0,
            lastClaimPlayer = null,
            lastClaimAmount = 0,
            pegEventTick = 0,
            lastPegEvent = null,
            scoreLog = emptyList(),
        )

        /** Lowercase canonical UUID, matching PROTOCOL.md's rule for the wire. */
        private fun randomMatchID(): String {
            val hex = "0123456789abcdef"
            fun run(n: Int) = buildString { repeat(n) { append(hex[Random.nextInt(16)]) } }
            return "${run(8)}-${run(4)}-4${run(3)}-a${run(3)}-${run(12)}"
        }
    }

    // ---- Transport event loop ----

    private fun listen() {
        eventsJob = scope.launch {
            transport.events.collect { handle(it) }
        }
    }

    private fun handle(event: TransportEvent) {
        when (event) {
            is TransportEvent.Connected -> {
                _connection.value = ConnectionState.CONNECTED
                onConnected()
                // On a *re*connect the game already exists — the host replays the current snapshot.
                if (isHost && state != null) refreshAndBroadcast()
            }
            is TransportEvent.Reconnecting -> _connection.value = ConnectionState.RECONNECTING
            is TransportEvent.Disconnected -> {
                _connection.value = ConnectionState.DISCONNECTED
                // Nearby games auto-rejoin after a drop. iOS treats an online match's drop as
                // terminal; Android has no online play, so nothing takes that branch here.
                if (isOnline) {
                    _opponentLeft.value = true
                    heartbeatJob?.cancel()
                }
            }
            is TransportEvent.Received -> receive(event.message)
        }
    }

    /**
     * A guest announces itself; the host waits for that hello before dealing.
     *
     * Re-sent until the host answers with a snapshot, so a first hello dropped during connection
     * setup can't leave the host stuck on "waiting for a player to join". The host treats a repeat
     * hello as a resync, so re-sending is safe.
     */
    private fun onConnected() {
        if (isHost) return
        scope.launch {
            for (attempt in 0 until HELLO_ATTEMPTS) {
                if (_snapshot.value.phase != GamePhase.CONNECTING) return@launch
                transport.send(
                    GameMessage.Hello(
                        name = localName,
                        colorID = localColorID,
                        playerToken = randomMatchID(),
                    ),
                )
                delay(if (attempt == 0) 1_000L else 2_000L)
            }
        }
    }

    private fun receive(message: GameMessage) {
        if (message is GameMessage.QuitGame) {   // the other player left — end for us too
            endGame()
            return
        }
        if (isHost) {
            when (message) {
                is GameMessage.Hello ->
                    if (state == null) {
                        startHostedGame(message.name, message.colorID)   // first join
                    } else {
                        refreshAndBroadcast()                            // reconnect resync
                    }
                else -> {
                    hostApply(message, fixedPlayer.opponent)   // the peer is the other player
                    refreshAndBroadcast()
                }
            }
        } else {
            when (message) {
                is GameMessage.Snapshot -> {
                    val previousPhase = _snapshot.value.phase
                    _snapshot.value = message.snapshot
                    fixedPlayer = message.snapshot.you
                    if (message.snapshot.phase != previousPhase) _selectedForDiscard.value = emptySet()
                    // Guest marker so this device can also offer "Rejoin game".
                    if (message.snapshot.phase == GamePhase.GAME_OVER) {
                        persistence.clear()
                    } else if (resumable && message.snapshot.phase != GamePhase.CONNECTING) {
                        persistence.saveMarker(isHost = false, summary = snapshotSummary(message.snapshot))
                    }
                }
                is GameMessage.AssignSeat -> fixedPlayer = message.player
                else -> Unit
            }
        }
    }

    private fun startHostedGame(guestName: String, guestColorID: Int) {
        val s = GameState.newMatch(
            matchID = randomMatchID(),
            seed = seed,
            names = mapOf(PlayerID.ONE to localName, PlayerID.TWO to guestName),
            colorIDs = mapOf(PlayerID.ONE to localColorID, PlayerID.TWO to guestColorID),
            scoringMode = scoringMode,
        )
        CribbageEngine.begin(s)
        state = s
        scope.launch { transport.send(GameMessage.AssignSeat(PlayerID.TWO)) }
        refreshAndBroadcast()
        startHeartbeat()
    }

    /**
     * The host re-broadcasts its authoritative snapshot every couple of seconds, so an update that
     * didn't reach the other device self-heals: the host keeps its state and keeps resending it.
     * Idempotent — the guest just re-renders the latest snapshot.
     */
    private fun startHeartbeat() {
        if (!isHost || isLoopback) return
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                val s = state ?: continue
                transport.send(GameMessage.Snapshot(s.snapshot(fixedPlayer.opponent)))
            }
        }
    }

    // ---- Viewer / rendering ----

    /**
     * Whose perspective the device currently shows. Loopback rotates to the acting player;
     * networked is fixed to the local device's player.
     */
    val viewer: PlayerID
        get() {
            val s = state
            return if (isLoopback && s != null) loopbackViewer(s) else fixedPlayer
        }

    private fun loopbackViewer(state: GameState): PlayerID = when (state.phase) {
        GamePhase.CUT_FOR_DEAL ->
            if (state.cutForDeal.size == 2) state.dealer                     // result shown → dealer deals
            else if (state.cutForDeal[PlayerID.ONE] == null) PlayerID.ONE else PlayerID.TWO
        GamePhase.DISCARD_TO_CRIB ->
            if (PlayerID.ONE in state.discarded) PlayerID.TWO else PlayerID.ONE
        // The pone lifts, then the dealer reveals.
        GamePhase.CUT_STARTER -> if (state.starterCutLifted) state.dealer else state.pone
        GamePhase.PEGGING -> state.whoseTurn ?: state.pone
        GamePhase.SHOW_PONE -> state.pone
        GamePhase.SHOW_DEALER, GamePhase.SHOW_CRIB -> state.dealer
        GamePhase.HAND_COMPLETE, GamePhase.GAME_OVER, GamePhase.DEALING, GamePhase.CONNECTING ->
            state.winner ?: PlayerID.ONE
    }

    private fun refreshAndBroadcast() {
        val s = state ?: return
        val renderPlayer = if (isLoopback) viewer else fixedPlayer
        if (renderPlayer != lastViewer) {
            _selectedForDiscard.value = emptySet()
            lastViewer = renderPlayer
        }
        _snapshot.value = s.snapshot(renderPlayer)
        if (isHost && !isLoopback) {
            val guestSnapshot = s.snapshot(fixedPlayer.opponent)
            scope.launch { transport.send(GameMessage.Snapshot(guestSnapshot)) }
        }
        // The host is the single source of truth for resume. Clear on game over.
        if (isHost && resumable) {
            if (s.phase == GamePhase.GAME_OVER) persistence.clear() else persistence.save(s)
        }
    }

    /** Save as the app is backgrounded. The host writes full state; a guest writes a marker. */
    fun persist() {
        if (!resumable) return
        val s = state
        if (isHost) {
            if (s != null && s.phase != GamePhase.GAME_OVER) persistence.save(s)
        } else {
            val snap = _snapshot.value
            if (snap.phase != GamePhase.CONNECTING && snap.phase != GamePhase.GAME_OVER) {
                persistence.saveMarker(isHost = false, summary = snapshotSummary(snap))
            }
        }
    }

    private fun snapshotSummary(s: PlayerSnapshot): String {
        val oneName = if (s.you == PlayerID.ONE) s.yourName else s.opponentName
        val oneScore = if (s.you == PlayerID.ONE) s.yourScore else s.opponentScore
        val twoName = if (s.you == PlayerID.ONE) s.opponentName else s.yourName
        val twoScore = if (s.you == PlayerID.ONE) s.opponentScore else s.yourScore
        return "$oneName $oneScore · $twoName $twoScore"
    }

    // ---- Derived UI helpers ----
    //
    // Computed from the current snapshot rather than stored, matching the Swift. Views read these
    // instead of picking the snapshot apart themselves.

    private val snap: PlayerSnapshot get() = _snapshot.value

    val runningCount: Int get() = snap.runningCount
    val isGameOver: Boolean get() = snap.phase == GamePhase.GAME_OVER

    /** An online game — one that can't be rejoined after a drop. Never true on Android in v1. */
    val isOnline: Boolean get() = !isLoopback && !resumable

    /** Both players have cut and the dealer is decided — show the result and "Deal". */
    val cutForDealDecided: Boolean
        get() = snap.phase == GamePhase.CUT_FOR_DEAL && snap.cutForDeal.size == 2

    val youNeedToCut: Boolean
        get() = snap.phase == GamePhase.CUT_FOR_DEAL && !cutForDealDecided && snap.cutForDeal[snap.you] == null

    val waitingForOpponentCut: Boolean
        get() = snap.phase == GamePhase.CUT_FOR_DEAL && !cutForDealDecided && snap.cutForDeal[snap.you] != null

    val youDeal: Boolean get() = cutForDealDecided && (isLoopback || snap.you == snap.dealer)

    /**
     * At hand-complete only the player who deals next starts the deal; the other sees a wait note.
     */
    val youStartNextDeal: Boolean
        get() = snap.phase == GamePhase.HAND_COMPLETE && (isLoopback || snap.you == snap.dealer.opponent)

    val nextDealer: PlayerID get() = snap.dealer.opponent

    val youLiftCut: Boolean
        get() = snap.phase == GamePhase.CUT_STARTER && !snap.starterCutLifted &&
            (isLoopback || snap.you == snap.pone)

    val youRevealStarter: Boolean
        get() = snap.phase == GamePhase.CUT_STARTER && snap.starterCutLifted &&
            (isLoopback || snap.you == snap.dealer)

    val starterCutLifted: Boolean get() = snap.starterCutLifted

    val pegEventTick: Int get() = snap.pegEventTick
    val lastPegEvent: PegEvent? get() = snap.lastPegEvent

    /**
     * The opponent is out of cards while you hold more than one — you keep laying on your own, so
     * say so, or it looks like the game is stuck on your turn.
     */
    val opponentOutKeepPlaying: Boolean
        get() = snap.phase == GamePhase.PEGGING && snap.isYourTurn &&
            snap.opponentHandCount == 0 && snap.yourHand.size > 1

    val peggingComplete: Boolean
        get() = snap.phase == GamePhase.PEGGING && snap.whoseTurn == null

    /**
     * Only the player who laid the last card moves on to the count, so they can peg their last-card
     * or go points first. The other player waits.
     */
    val youStartCount: Boolean
        get() = peggingComplete && (isLoopback || snap.lastToPlay == null || snap.you == snap.lastToPlay)

    /** Which pegs this device may score. Loopback shows both; networked only the local player's. */
    val scorablePlayers: List<PlayerID>
        get() = if (isLoopback) listOf(PlayerID.ONE, PlayerID.TWO) else listOf(snap.you)

    fun isLegalPlay(card: Card): Boolean =
        snap.phase == GamePhase.PEGGING && snap.isYourTurn &&
            snap.runningCount + card.countingValue <= 31

    val canSayGo: Boolean
        get() = snap.phase == GamePhase.PEGGING && snap.isYourTurn &&
            CribbageScorer.legalPlays(snap.yourHand, snap.runningCount).isEmpty()

    val canConfirmDiscard: Boolean
        get() = snap.phase == GamePhase.DISCARD_TO_CRIB && _selectedForDiscard.value.size == 2

    /** True when this device may act; pass-and-play is always "connected". */
    val canActNow: Boolean get() = _connection.value == ConnectionState.CONNECTED

    /**
     * The other player is reachable right now, so a networked command will actually be delivered.
     * Mirrors the guard in [submit], which silently drops intents while disconnected — "Play again"
     * needs to know that in advance so it can say so rather than appear to do nothing.
     */
    val opponentAvailable: Boolean
        get() = isLoopback || _connection.value == ConnectionState.CONNECTED

    // ---- The show (counting) ----

    /** Who is counting now: pone, then dealer, then the dealer's crib. Null outside the show. */
    val showCountingPlayer: PlayerID?
        get() = when (snap.phase) {
            GamePhase.SHOW_PONE -> snap.pone
            GamePhase.SHOW_DEALER, GamePhase.SHOW_CRIB -> snap.dealer
            else -> null
        }

    val youAreCounting: Boolean get() = showCountingPlayer == snap.you

    /**
     * Who the current scoring feedback belongs to — during pegging whoever just played, during the
     * show the counter. Drives the flag colour and the leading name.
     */
    val scoringPlayer: PlayerID?
        get() = when (snap.phase) {
            GamePhase.PEGGING -> snap.lastToPlay ?: snap.dealer
            GamePhase.SHOW_PONE -> snap.pone
            GamePhase.SHOW_DEALER, GamePhase.SHOW_CRIB -> snap.dealer
            else -> null
        }

    /** During the show only the counting player may score; the other panel is inert. */
    fun scoringDisabled(player: PlayerID): Boolean = when (snap.phase) {
        GamePhase.SHOW_PONE, GamePhase.SHOW_DEALER, GamePhase.SHOW_CRIB -> player != showCountingPlayer
        else -> false
    }

    /**
     * The cards being counted, resolved from this device's snapshot. Both devices see the same
     * hand — the counter's own, the watcher's via the revealed opponent hand.
     */
    val showCards: List<Card>
        get() = when (snap.phase) {
            GamePhase.SHOW_PONE ->
                if (snap.pone == snap.you) snap.yourHand else (snap.opponentHand ?: emptyList())
            GamePhase.SHOW_DEALER ->
                if (snap.dealer == snap.you) snap.yourHand else (snap.opponentHand ?: emptyList())
            GamePhase.SHOW_CRIB -> snap.crib ?: emptyList()
            else -> emptyList()
        }

    /** Name-based label for what's being counted — never the "pone/dealer" jargon. */
    val showLabel: String
        get() = when (snap.phase) {
            GamePhase.SHOW_PONE -> "${name(snap.pone)}'s hand"
            GamePhase.SHOW_DEALER -> "${name(snap.dealer)}'s hand"
            GamePhase.SHOW_CRIB -> "${name(snap.dealer)}'s crib"
            else -> ""
        }

    /**
     * The correct count for what's on the table, computed on this device from the revealed cards.
     * Powers "check my count" so a manual counter can verify themselves.
     */
    val checkScoreFlags: List<ScoreFlag>
        get() {
            val starter = snap.starter ?: return emptyList()
            return when (snap.phase) {
                GamePhase.SHOW_PONE, GamePhase.SHOW_DEALER ->
                    CribbageScorer.handBreakdown(showCards, starter, isCrib = false)
                GamePhase.SHOW_CRIB ->
                    CribbageScorer.handBreakdown(showCards, starter, isCrib = true)
                else -> emptyList()
            }
        }

    val checkScoreTotal: Int get() = checkScoreFlags.totalPoints

    /** The finished game's scoring history, driving the win-screen replay. Empty until game over. */
    val scoreLog: List<Claim> get() = snap.scoreLog

    val coachBanner: String
        get() {
            val s = snap
            return when (s.phase) {
                GamePhase.CONNECTING ->
                    if (isHost) "Waiting for a player to join…" else "Connecting…"
                GamePhase.CUT_FOR_DEAL -> when {
                    cutForDealDecided -> "${name(s.dealer)} wins the cut — deals & takes the crib"
                    waitingForOpponentCut -> "Waiting for ${s.opponentName} to cut…"
                    else -> "${s.yourName}, cut for deal"
                }
                GamePhase.DEALING -> "Dealing…"
                GamePhase.DISCARD_TO_CRIB -> {
                    val whose = if (s.yourSeat == Seat.DEALER) "your crib" else "${name(s.dealer)}'s crib"
                    "${s.yourName}, discard 2 to $whose"
                }
                GamePhase.CUT_STARTER ->
                    if (s.starterCutLifted) {
                        if (youRevealStarter) "${name(s.dealer)}, turn up the cut"
                        else "Waiting for ${name(s.dealer)} to turn up the cut…"
                    } else {
                        if (youLiftCut) "${name(s.pone)}, cut the deck"
                        else "Waiting for ${name(s.pone)} to cut the deck…"
                    }
                GamePhase.PEGGING -> when {
                    peggingComplete -> "All cards played — count the hands"
                    opponentOutKeepPlaying -> "${s.opponentName} is out — keep laying your cards"
                    s.isYourTurn ->
                        if (canSayGo) "${s.yourName}: no card to play — say Go" else "${s.yourName}'s play"
                    else -> "Waiting for ${s.opponentName}"
                }
                GamePhase.SHOW_PONE -> "${name(s.pone)} counts their hand"
                GamePhase.SHOW_DEALER -> "${name(s.dealer)} counts their hand"
                GamePhase.SHOW_CRIB -> "${name(s.dealer)} counts the crib"
                GamePhase.HAND_COMPLETE -> "Hand complete"
                GamePhase.GAME_OVER -> {
                    val w = if (s.winner == s.you) s.yourName else s.opponentName
                    "$w wins!"
                }
            }
        }

    fun name(player: PlayerID): String =
        if (player == snap.you) snap.yourName else snap.opponentName

    fun colorID(player: PlayerID): Int =
        if (player == snap.you) snap.yourColorID else snap.opponentColorID

    fun score(player: PlayerID): Int =
        if (player == snap.you) snap.yourScore else snap.opponentScore

    fun canUndo(player: PlayerID): Boolean = player in snap.playersWithClaims

    /** The winner and how badly the loser lost, or null while the game is still on. */
    val winnerInfo: Pair<PlayerID, SkunkLevel>?
        get() {
            val winner = snap.winner ?: return null
            return winner to SkunkLevel.of(score(winner.opponent))
        }

    // ---- Intents (from the views) ----

    fun cut() {
        cutCounter = (cutCounter * 31 + 7).mod(4999)
        submit(GameMessage.IntentCut(cutCounter))
    }

    /** The pone lifts a portion of the deck for the starter cut (step 1 of 2). */
    fun liftCut() {
        cutCounter = (cutCounter * 31 + 7).mod(4999)
        submit(GameMessage.IntentLiftCut(cutCounter))
    }

    /** The dealer turns up the starter after the pone has lifted (step 2 of 2). */
    fun revealStarter() = submit(GameMessage.IntentRevealStarter)

    fun toggleDiscard(card: Card) {
        val current = _selectedForDiscard.value
        _selectedForDiscard.value = when {
            card in current -> current - card
            current.size < 2 -> current + card
            else -> current
        }
    }

    fun confirmDiscard() {
        if (!canConfirmDiscard) return
        val cards = _selectedForDiscard.value.toList()
        _selectedForDiscard.value = emptySet()
        submit(GameMessage.IntentDiscard(cards))
    }

    fun play(card: Card) {
        if (!isLegalPlay(card)) return
        submit(GameMessage.IntentPlay(card))
    }

    /**
     * Called when the app returns to the foreground. [force] rebuilds even if the transport still
     * believes it is connected, so we don't wait out the OS's slow drop detection.
     */
    fun reconnect(force: Boolean = false) = transport.reconnect(force)

    fun sayGo() = submit(GameMessage.IntentGo)

    fun claim(amount: Int, player: PlayerID) = submit(GameMessage.ClaimPoints(player, amount))

    fun undo(player: PlayerID) = submit(GameMessage.Undo(player))

    fun advance() = submit(GameMessage.Advance)

    fun playAgain() = submit(GameMessage.PlayAgain)

    /**
     * A live name/colour change from Settings, propagated into the running game.
     *
     * Deliberately *not* routed through [submit], which attributes a message to the [viewer] — the
     * player who must act next. That is right for an intent and wrong for an identity: in
     * pass-and-play the viewer rotates, so renaming yourself from Settings mid-hand renamed
     * whichever player happened to be on turn, and could leave your own name unchanged while the
     * opponent took it. Identity always belongs to [fixedPlayer], which in a loopback game is the
     * player this device set up as itself.
     */
    fun updateLocalIdentity(name: String, colorID: Int) {
        if (name == localName && colorID == localColorID) return
        localName = name
        localColorID = colorID
        if (!isLoopback && _connection.value != ConnectionState.CONNECTED) return
        val message = GameMessage.UpdateIdentity(name, colorID)
        if (isHost) {
            hostApply(message, fixedPlayer)
            refreshAndBroadcast()
        } else {
            scope.launch { transport.send(message) }
        }
    }

    /** A live scoring-mode change from Settings. Either device may set it; it governs both. */
    fun setScoringMode(mode: ScoringMode) {
        // Compared against the *game's* mode, not this device's [scoringMode] field: a guest seeds
        // that field from its own stored setting, so guarding on it would wrongly no-op whenever the
        // guest tries to change a game the host started in a different mode — the guest's flags then
        // stayed on after it switched to player responsibility.
        if (mode == _snapshot.value.scoringMode) return
        scoringMode = mode
        submit(GameMessage.SetScoringMode(mode.value))
    }

    // ---- Intent plumbing ----

    /**
     * The host applies locally and broadcasts; a guest forwards to the host. Intents are ignored
     * while a networked game is disconnected — pass-and-play is always "connected".
     */
    private fun submit(message: GameMessage) {
        if (!isLoopback && _connection.value != ConnectionState.CONNECTED) return
        if (isHost) {
            hostApply(message, if (isLoopback) viewer else fixedPlayer)
            refreshAndBroadcast()
        } else {
            scope.launch { transport.send(message) }
        }
    }

    private fun hostApply(message: GameMessage, player: PlayerID) {
        var s = state ?: return
        when (message) {
            is GameMessage.IntentCut ->
                if (s.phase == GamePhase.CUT_FOR_DEAL) CribbageEngine.cutForDeal(s, player, message.index)
            is GameMessage.IntentDiscard -> CribbageEngine.discard(s, player, message.cards)
            is GameMessage.IntentPlay ->
                if (s.whoseTurn == player) CribbageEngine.play(s, player, message.card)
            is GameMessage.IntentGo ->
                if (s.whoseTurn == player) CribbageEngine.go(s, player)
            is GameMessage.IntentLiftCut -> CribbageEngine.liftStarterCut(s, player, message.index)
            is GameMessage.IntentRevealStarter -> CribbageEngine.revealStarter(s, player)
            is GameMessage.ClaimPoints -> {
                // Networked: a device may only score its own peg. Loopback: either peg.
                val target = if (isLoopback) message.player else player
                CribbageEngine.claim(s, target, message.amount)
            }
            is GameMessage.Undo -> {
                val target = if (isLoopback) message.player else player
                CribbageEngine.undo(s, target)
            }
            is GameMessage.Advance -> CribbageEngine.advance(s)
            // playAgain replaces the state wholesale rather than mutating it.
            is GameMessage.PlayAgain -> s = CribbageEngine.playAgain(s)
            is GameMessage.UpdateIdentity -> {
                s.names = s.names + (player to message.name)
                s.colorIDs = s.colorIDs + (player to message.colorID)
            }
            is GameMessage.SetScoringMode -> s.scoringMode = ScoringMode.of(message.mode)
            is GameMessage.Hello,
            is GameMessage.AssignSeat,
            is GameMessage.Snapshot,
            is GameMessage.QuitGame,
            -> Unit
        }
        state = s
    }

    // ---- Quit ----

    /**
     * Leave for good. Tells the other player so their app ends too, then clears the saved game and
     * marks this finished, which the view observes to return to the menu.
     */
    fun quit() {
        if (_ended.value) return
        if (isLoopback) {
            endGame()
            return
        }
        scope.launch {
            transport.send(GameMessage.QuitGame)
            delay(QUIT_FLUSH_MS)   // let the message flush before teardown
            endGame()
        }
    }

    private fun endGame() {
        if (_ended.value) return
        heartbeatJob?.cancel()
        persistence.clear()
        _ended.value = true
    }

    /** Release the event loop and heartbeat. The Android equivalent of iOS's `deinit`. */
    fun dispose() {
        eventsJob?.cancel()
        heartbeatJob?.cancel()
    }
}
