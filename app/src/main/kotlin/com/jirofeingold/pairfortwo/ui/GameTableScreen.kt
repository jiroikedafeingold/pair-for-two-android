package com.jirofeingold.pairfortwo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jirofeingold.pairfortwo.feel.GameFeedback
import com.jirofeingold.pairfortwo.feel.HapticPatterns
import com.jirofeingold.pairfortwo.core.GamePhase
import com.jirofeingold.pairfortwo.core.GameViewModel
import com.jirofeingold.pairfortwo.core.PlayerID
import com.jirofeingold.pairfortwo.core.PlayerSnapshot
import com.jirofeingold.pairfortwo.core.ScoringMode
import com.jirofeingold.pairfortwo.core.Seat
import com.jirofeingold.pairfortwo.core.sortedForDisplay
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.FeltMid
import com.jirofeingold.pairfortwo.ui.theme.playerTheme

/**
 * The root game screen — port of the iOS `GameTableView`.
 *
 * The top band is the scoreboard, coach banner and flag chips; the rest is the shared play area and
 * the current player's hand. Every card size is derived from the available geometry exactly as the
 * Swift's `GeometryReader` does, so the same layout simply grows on a tablet with no device checks.
 *
 * ## Not yet ported
 *
 * - The check-my-count overlay.
 *
 * @param confirmRelease the "Confirm after release" setting: the slider stages an amount and the
 *   +N button commits it, rather than scoring the moment a thumb lifts. Applies to *every* panel on
 *   screen — in pass-and-play both players get the same gesture, which is the whole point of it
 *   being a setting rather than a property of whose panel it is.
 * @param scoreTrackEnabled "Score progress rings" — the cribbage track drawn around each panel.
 * @param celebrationEffects "Celebration effects" — fireworks and confetti on the win screen. The
 *   win screen itself always shows.
 * @param replayBeforeWin replay the game's scoring before revealing the win screen.
 * @param onOpenSettings opens the settings screen from the table's top-right control.
 * @param onOpenHelp opens the how-to-play guide from the control beside it.
 * @param onExit leaves the game and returns to whatever presented the table. Null when there is
 *   nowhere to go — the quit control, the back handler and the overlays' "Back to menu" all hide
 *   themselves rather than pretend to offer a way out.
 */
@Composable
fun GameTableScreen(
    vm: GameViewModel,
    modifier: Modifier = Modifier,
    feedback: GameFeedback? = null,
    confirmRelease: Boolean = true,
    scoreTrackEnabled: Boolean = true,
    celebrationEffects: Boolean = true,
    replayBeforeWin: Boolean = true,
    onOpenSettings: (() -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
    onExit: (() -> Unit)? = null,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val selected by vm.selectedForDiscard.collectAsStateWithLifecycle()
    val ended by vm.ended.collectAsStateWithLifecycle()
    val opponentLeft by vm.opponentLeft.collectAsStateWithLifecycle()
    var playAgainUnavailable by remember { mutableStateOf(false) }
    var showQuitConfirm by remember { mutableStateOf(false) }
    // The auto replay shown *before* the win screen has run; `showManualReplay` is the win screen's
    // own "Replay scoring".
    var preWinReplayShown by remember { mutableStateOf(false) }
    var showManualReplay by remember { mutableStateOf(false) }

    // A fresh hand or a rematch re-arms the pre-win replay for the next game over.
    LaunchedEffect(snapshot.phase) {
        if (snapshot.phase == GamePhase.DISCARD_TO_CRIB) {
            preWinReplayShown = false
            showManualReplay = false
        }
    }

    // The game was quit — by this player or the other one — so hand back to whoever presented us.
    LaunchedEffect(ended) { if (ended) onExit?.invoke() }

    // Points staged on a panel but not yet claimed, per player — pass-and-play shows a panel each
    // and either can be mid-stage. Continue folds them in, so a last-card or go point can't be
    // stranded by moving the game on — the same reason iOS tracks it.
    val uncommitted = remember { mutableStateMapOf<PlayerID, Int>() }
    var clearSignal by remember { mutableIntStateOf(0) }
    val commitThenAdvance: () -> Unit = {
        // Stable claim order, so which peg lands first at 121 never depends on map iteration.
        val staged = PlayerID.entries
            .mapNotNull { player -> uncommitted[player]?.takeIf { it > 0 }?.let { player to it } }
        if (staged.isNotEmpty()) {
            staged.forEach { (player, amount) -> vm.claim(amount, player) }
            clearSignal += 1
            uncommitted.clear()
        }
        vm.advance()
    }

    // The insets are *measured* here but applied further in, to the two bands' contents rather
    // than to the table as a whole. Padding the whole table would inset its backgrounds too, and
    // the scoring band is meant to be a full-bleed strip across the screen — inset, it reads as a
    // floating panel with felt down either side. The app runs fullscreen, so on most devices these
    // are zero anyway; they still matter for a display cutout, and for the moment after a swipe
    // brings the system bars back.
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val insetStart = safeInsets.calculateLeftPadding(layoutDirection)
    val insetEnd = safeInsets.calculateRightPadding(layoutDirection)
    val insetTop = safeInsets.calculateTopPadding()
    val insetBottom = safeInsets.calculateBottomPadding()

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeltMid, FeltDark))),
    ) {
        // Budgets are computed from the *usable* area, so hiding the insets from the backgrounds
        // doesn't quietly hand the cards space they can't actually occupy.
        val height = maxHeight - insetTop - insetBottom
        val width = maxWidth - insetStart - insetEnd

        // The same budget the Swift computes. Capping the band stops it leaving a tall dead zone on
        // a tablet; the play area takes whatever is left.
        val topBandHeight = minOf(height * 0.37f, 190.dp)
        // Card budgets still assume the band takes its full cap, so a band that wraps smaller only
        // ever leaves *more* room than the cards were sized for — never less.
        val playHeight = height - topBandHeight

        // A tablet, in the sense Android already means by `sw600dp`: in a landscape-locked app the
        // smallest dimension is the height, so that — not the width — is what separates a tablet
        // from a phone held sideways. This is the Android reading of the `hSizeClass == .regular`
        // branches the Swift added; a landscape phone is ~400dp tall and compact on both platforms.
        val isTablet = height >= 600.dp

        // Every phase reserves a fixed trailing "action rail" for its flags, prompt and button, so
        // nothing stacks below the cards and the action can never run off the bottom of a short
        // landscape phone. A tablet gets a far wider rail — at the phone's width it was a thin
        // ribbon against all that felt.
        val railWidth = if (isTablet) minOf(width * 0.30f, 420.dp) else 156.dp
        val playWidth = width - railWidth

        // Card aspect is height = width × 1.45. Each phase's cards fill as much of the play column
        // as its own layout allows. Discard: a six-card hand. Pegging: a pile above the hand, so
        // shorter. Show: the cut plus a four-card row.
        val handWidth = minOf((playWidth - 34.dp) / 7f, (playHeight - 64.dp) / 1.45f)
        val peggingHandWidth = minOf(handWidth, (playHeight - 44.dp) / 2.15f)
        val pileWidth = peggingHandWidth * 0.5f
        // The show row is the cut card + a 16dp gap + a four-card hand at 8dp spacing — five cards
        // and ~44dp — so dividing by five keeps it inside the play column instead of spilling into
        // the rail.
        val showWidth = minOf((playWidth - 44.dp) / 5f, (playHeight - 40.dp) / 1.45f)
        // The two cut screens hold just two cards, so they'd balloon on a tablet — halve them there.
        val cutBase = minOf((playWidth - 50.dp) / 2.2f, (playHeight - 76.dp) / 1.45f)
        val cutWidth = if (isTablet) cutBase * 0.5f else cutBase

        val winner = vm.winnerInfo
        // iOS puts the game-over card over an .ultraThinMaterial, which blurs the table rather than
        // merely dimming it. Compose has no equivalent material, so the table itself is blurred.
        // Modifier.blur is a no-op below API 31, where the scrim alone still reads clearly.
        val tableBlur by animateDpAsState(
            if (winner != null) 18.dp else 0.dp,
            tween(500),
            label = "tableBlur",
        )
        Column(Modifier.fillMaxSize().blur(tableBlur)) {
            // The Swift pins this band to a fixed height and clips. Here it sizes to its content
            // up to the same cap instead: Android's text measures a little taller, and at a fixed
            // height the scoreboard's digits were sliced off the moment the flag chips appeared.
            // Wrapping also means the band is shorter when there are no flags, which hands the play
            // area more room rather than leaving a dead strip.
            TopBand(
                vm = vm,
                s = snapshot,
                feedback = feedback,
                confirmRelease = confirmRelease,
                scoreTrackEnabled = scoreTrackEnabled,
                clearSignal = clearSignal,
                onUncommittedChange = { player, amount -> uncommitted[player] = amount },
                modifier = Modifier
                    .fillMaxWidth()
                    // The band's own height plus whatever sits above it, so the dark strip runs
                    // edge to edge and up under a cutout rather than stopping short of it.
                    .heightIn(max = topBandHeight + insetTop)
                    .background(Color.Black.copy(alpha = 0.22f))
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .clipToBounds(),
            )
            BottomBand(
                vm = vm,
                s = snapshot,
                selected = selected,
                uncommitted = uncommitted.values.sum(),
                commitThenAdvance = commitThenAdvance,
                isTablet = isTablet,
                railWidth = railWidth,
                handWidth = handWidth,
                peggingHandWidth = peggingHandWidth,
                pileWidth = pileWidth,
                showWidth = showWidth,
                cutWidth = cutWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(vertical = 14.dp),
            )
        }

        // The top-right controls, over the band: help and settings, as iOS pairs them.
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onOpenHelp != null) {
                ControlButton(
                    onClick = onOpenHelp,
                    description = "How to play",
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                )
            }
            if (onOpenSettings != null) {
                ControlButton(
                    onClick = onOpenSettings,
                    description = "Settings",
                    icon = Icons.Filled.Settings,
                )
            }
        }

        // Leaving ends the game for *both* players, so it asks first — and back does the same thing
        // rather than silently abandoning a live game.
        if (onExit != null) {
            ControlButton(
                onClick = { showQuitConfirm = true },
                description = "Quit game",
                icon = Icons.Filled.Close,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 6.dp, start = 10.dp),
            )
            BackHandler(enabled = !showQuitConfirm) { showQuitConfirm = true }
        }

        if (showQuitConfirm) {
            AlertDialog(
                onDismissRequest = { showQuitConfirm = false },
                title = { Text("Quit this game?") },
                text = { Text("The game ends for both players.") },
                confirmButton = {
                    TextButton(onClick = { showQuitConfirm = false; vm.quit() }) {
                        Text("Quit game", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showQuitConfirm = false }) { Text("Keep playing") }
                },
            )
        }

        // Whether to auto-play the replay before revealing the win screen — computed in the *same*
        // composition that sees the winner, never set afterwards. That ordering is the whole point:
        // if it were decided in an effect, the win overlay (and the multi-second celebration haptic
        // its LaunchedEffect fires) would be inserted for a frame underneath the replay.
        val scoreLog = snapshot.scoreLog
        val wantsPreWinReplay =
            winner != null && replayBeforeWin && scoreLog.isNotEmpty() && !preWinReplayShown

        if (winner != null && !wantsPreWinReplay) {
            val (who, skunk) = winner
            // Pass-and-play has one device and one screen, so it always shows the celebration.
            // Networked, each device shows its own outcome.
            val youWon = vm.isLoopback || who == snapshot.you
            LaunchedEffect(who, skunk, youWon) {
                if (youWon) feedback?.playWin(skunk) else feedback?.playLose()
            }
            // Tapping "Play again" while the other device is unreachable would post an intent into
            // the void — the view model drops intents while disconnected — and the screen would just
            // sit there. Say so instead, and head home.
            val playAgain: () -> Unit = {
                feedback?.stopCelebration()
                if (vm.opponentAvailable) vm.playAgain() else playAgainUnavailable = true
            }
            if (youWon) {
                WinnerOverlay(
                    winnerName = vm.name(who),
                    skunk = skunk,
                    winnerColor = playerTheme(vm.colorID(who)).primary,
                    celebrationEffects = celebrationEffects,
                    onPlayAgain = playAgain,
                    onExit = onExit?.let { { vm.quit() } },
                    opponentLeft = opponentLeft,
                    onReplay = if (scoreLog.isNotEmpty()) {
                        { feedback?.stopCelebration(); showManualReplay = true }
                    } else {
                        null
                    },
                )
            } else {
                LoserOverlay(
                    winnerName = vm.name(who),
                    skunk = skunk,
                    celebrationEffects = celebrationEffects,
                    onPlayAgain = playAgain,
                    onExit = onExit?.let { { vm.quit() } },
                    opponentLeft = opponentLeft,
                    onReplay = if (scoreLog.isNotEmpty()) { { showManualReplay = true } } else null,
                )
            }
        }

        if (wantsPreWinReplay || showManualReplay) {
            ScoringReplay(
                events = scoreLog,
                nameOne = vm.name(PlayerID.ONE),
                nameTwo = vm.name(PlayerID.TWO),
                themeOne = playerTheme(vm.colorID(PlayerID.ONE)),
                themeTwo = playerTheme(vm.colorID(PlayerID.TWO)),
                feedback = feedback,
                onFinish = {
                    // Pre-win: reveal the win screen, which fires its own celebration then.
                    // Manual: just dismiss back to the win screen already underneath.
                    if (wantsPreWinReplay) preWinReplayShown = true
                    showManualReplay = false
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (playAgainUnavailable) {
            PlayAgainUnavailableOverlay(
                onBack = { if (onExit != null) vm.quit() else playAgainUnavailable = false },
                canGoHome = onExit != null,
            )
        }
    }
}

/**
 * Shown when "Play again" is tapped but the other device isn't reachable.
 *
 * iOS returns to the menu on its own after a couple of seconds. Here the button is the way out,
 * because until the menu is ported there is nowhere to auto-return *to* — with no exit available it
 * dismisses back to the win screen so the player isn't trapped behind a scrim.
 */
@Composable
private fun PlayAgainUnavailableOverlay(onBack: () -> Unit, canGoHome: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Opponent unavailable", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "The other player isn't available for another game.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            GoldButton(if (canGoHome) "Back to menu" else "OK", onBack)
        }
    }
}

/** The table's own chrome button — iOS's `controlButton`: a 32dp dark disc, dimmed white glyph. */
@Composable
private fun ControlButton(
    onClick: () -> Unit,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            // The disc is 32dp because that is what reads over the felt; the target is padded to
            // the 48dp minimum so it can still be hit reliably.
            .minimumInteractiveComponentSize()
            .size(32.dp)
            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---- Top band ----

@Composable
private fun TopBand(
    vm: GameViewModel,
    s: PlayerSnapshot,
    feedback: GameFeedback?,
    confirmRelease: Boolean,
    scoreTrackEnabled: Boolean,
    clearSignal: Int,
    onUncommittedChange: (PlayerID, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            vm.coachBanner,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = tightTextStyle(17.sp, FontWeight.Bold),
            modifier = Modifier
                .fillMaxWidth()
                // Keep clear of the top controls and the screen edges.
                .padding(horizontal = 44.dp),
        )

        // The scoring flags ("Fifteen 2 +2" …) used to sit here. They live in the play area's action
        // rail now, so this dark band holds only the coach line and the scoreboard — which is what
        // gives the scores room to be read across a table.

        if (s.scoringMode == ScoringMode.AUTO) {
            // Automatic scoring has no manual controls — just names and scores.
            AutoScoreboard(vm, s)
        } else {
            // A panel per peg this device may score: both in pass-and-play, only the local
            // player's when networked. Capped so a lone panel doesn't stretch across a tablet.
            Row(
                Modifier
                    .widthIn(max = 900.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (player in vm.scorablePlayers) {
                    val theme = playerTheme(vm.colorID(player))
                    ScorePanel(
                        name = vm.name(player),
                        score = vm.score(player),
                        opponentScore = vm.score(player.opponent),
                        primary = theme.primary,
                        deep = theme.deep,
                        disabled = s.phase == GamePhase.GAME_OVER || vm.scoringDisabled(player),
                        canUndo = vm.canUndo(player),
                        // Every panel obeys the setting. iOS applies it to the local panel only
                        // (`isLocal ? confirmRelease : false`), which in pass-and-play gives the
                        // two players different gestures on the same screen — one stages, one
                        // scores on release. Deliberate divergence.
                        requireConfirm = confirmRelease,
                        opponentColor = playerTheme(vm.colorID(player.opponent)).primary,
                        showOpponentTrack = vm.scorablePlayers.size == 1,
                        showScoreTrack = scoreTrackEnabled,
                        onUncommittedChange = { onUncommittedChange(player, it) },
                        clearSignal = clearSignal,
                        onTick = { feedback?.sliderTick(it) },
                        onPlusHaptic = { feedback?.play(HapticPatterns.Action.SCORE) },
                        onCommitHaptic = { feedback?.play(HapticPatterns.Action.SCORE) },
                        onAdd = { vm.claim(it, player) },
                        onPlusOne = { vm.claim(1, player) },
                        onUndo = { vm.undo(player) },
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp),
                    )
                }
            }
        }
    }
}

/** Each player's name over a big score, in their colour. */
@Composable
private fun AutoScoreboard(vm: GameViewModel, s: PlayerSnapshot) {
    Row(
        Modifier
            .widthIn(max = 760.dp)
            .padding(horizontal = 34.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreColumn(vm, s.you, Modifier.weight(1f))
        // A soft-capped bar down the middle, distinctly heavier than the thin progress ring around
        // the pair — at a hairline it read as a third track rather than as a separator.
        Box(
            Modifier
                .width(2.5.dp)
                .height(58.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.06f),
                        ),
                    ),
                    CircleShape,
                ),
        )
        ScoreColumn(vm, s.you.opponent, Modifier.weight(1f))
    }
}

@Composable
private fun ScoreColumn(vm: GameViewModel, player: PlayerID, modifier: Modifier = Modifier) {
    val theme = playerTheme(vm.colorID(player))
    Column(
        // Horizontal padding keeps the names and scores clear of the divider and of the ends of the
        // oval the progress ring traces around them.
        modifier.padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            vm.name(player).uppercase(),
            color = theme.primary,
            maxLines = 1,
            style = tightTextStyle(18.sp, FontWeight.Black),
        )
        Text(
            "${vm.score(player)}",
            color = Color.White,
            maxLines = 1,
            style = tightTextStyle(44.sp, FontWeight.Black),
        )
    }
}

// ---- Bottom band ----

@Composable
private fun BottomBand(
    vm: GameViewModel,
    s: PlayerSnapshot,
    selected: Set<com.jirofeingold.pairfortwo.core.Card>,
    uncommitted: Int,
    commitThenAdvance: () -> Unit,
    isTablet: Boolean,
    railWidth: Dp,
    handWidth: Dp,
    peggingHandWidth: Dp,
    pileWidth: Dp,
    showWidth: Dp,
    cutWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (s.phase) {
            GamePhase.CUT_FOR_DEAL -> CutForDealArea(vm, s, cutWidth, railWidth)
            GamePhase.DISCARD_TO_CRIB -> DiscardArea(vm, s, selected, handWidth, railWidth)
            GamePhase.CUT_STARTER -> StarterCutArea(vm, s, cutWidth, railWidth)
            GamePhase.PEGGING ->
                PeggingArea(
                    vm, s, peggingHandWidth, pileWidth, railWidth, uncommitted, commitThenAdvance, isTablet,
                )
            GamePhase.SHOW_PONE, GamePhase.SHOW_DEALER, GamePhase.SHOW_CRIB ->
                ShowArea(vm, s, showWidth, railWidth, uncommitted, commitThenAdvance)
            GamePhase.HAND_COMPLETE -> HandCompleteArea(vm, s, railWidth)
            GamePhase.GAME_OVER -> Spacer(Modifier.fillMaxSize())   // the overlay covers this
            else -> Spacer(Modifier.fillMaxSize())
        }
    }
}

/**
 * One consistent landscape layout for every phase of play — port of the iOS `playScene`.
 *
 * The cards fill and centre whatever space is left, while the phase's prompt, status and primary
 * button sit in a fixed-width column on the trailing side, in the same place on every screen.
 * Nothing stacks below the cards, so the action can never run off the bottom of a short landscape
 * phone — which is what the old vertically-stacked layout kept doing as soon as a hand grew.
 *
 * The rail's width is **always** reserved, whether or not it currently holds anything, so the cards
 * keep a fixed centred position and don't jump the moment a "Go" button or a wait message appears.
 */
@Composable
private fun PlayScene(
    vm: GameViewModel,
    s: PlayerSnapshot,
    railWidth: Dp,
    play: @Composable () -> Unit,
    action: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) { play() }

        Column(
            Modifier
                .width(railWidth)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The flag band is always reserved, flags or not, so the prompt and button below it sit
            // in the same place all game and never jump as scoring feedback appears.
            //
            // iOS instead floats the flags over a rail-centred button, which keeps the button level
            // with the cards. That doesn't survive the move to Android: the same chips and prompts
            // measure taller here, and on the show screen the flag column landed straight on top of
            // "Count it on your slider, then Continue". Reserving the band costs a little vertical
            // alignment with the cards and buys back legibility.
            Box(Modifier.height(FLAG_BAND_HEIGHT)) {
                RailFlags(vm, s, Modifier.align(Alignment.TopCenter))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                content = action,
            )
        }
    }
}

/** The rail's reserved height for scoring flags — iOS's 96pt cap on the same column. */
private val FLAG_BAND_HEIGHT = 96.dp

/**
 * The scoring flags for the current context, as a column pinned to the top of the rail.
 *
 * Height-capped and scrollable: a big hand's list of fifteens and runs is easily a dozen chips, and
 * left to itself it would run the length of the screen.
 */
@Composable
private fun RailFlags(vm: GameViewModel, s: PlayerSnapshot, modifier: Modifier = Modifier) {
    if (s.flags.isEmpty()) return
    val scoringPlayer = vm.scoringPlayer
    ScoreFlagsView(
        flags = s.flags,
        accent = scoringPlayer?.let { playerTheme(vm.colorID(it)).primary } ?: CribGold,
        playerName = scoringPlayer?.let { vm.name(it) },
        vertical = true,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = FLAG_BAND_HEIGHT),
    )
}

/**
 * Each player cuts once and their card is shown to both. Once both have cut, the lower card wins the
 * deal and the first crib; the dealer then taps Deal.
 */
@Composable
private fun CutForDealArea(vm: GameViewModel, s: PlayerSnapshot, width: Dp, railWidth: Dp) {
    PlayScene(
        vm, s, railWidth,
        play = {
            Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                CutResult(vm, s, PlayerID.ONE, width)
                CutResult(vm, s, PlayerID.TWO, width)
            }
        },
    ) {
        when {
            vm.cutForDealDecided ->
                if (vm.youDeal) GoldButton("Deal") { vm.advance() }
                else WaitingLabel("Waiting for ${vm.name(s.dealer)} to deal…")
            vm.youNeedToCut -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.tappable { vm.cut() },
            ) {
                CardView(null, faceUp = false, width = width * 0.85f)
                Text(
                    "Tap to cut",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = tightTextStyle(15.sp, FontWeight.SemiBold),
                )
            }
            else -> WaitingLabel("Waiting for ${s.opponentName} to cut…")
        }
    }
}

@Composable
private fun CutResult(vm: GameViewModel, s: PlayerSnapshot, player: PlayerID, width: Dp) {
    val isWinner = vm.cutForDealDecided && s.dealer == player
    Column(
        Modifier.width(width + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            vm.name(player),
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            style = tightTextStyle(12.sp),
        )
        val card = s.cutForDeal[player]
        if (card != null) {
            CardView(card, isHighlighted = isWinner, width = width)
        } else {
            CardView(null, faceUp = false, width = width, modifier = Modifier.alpha(0.35f))
        }
        Text(
            if (isWinner) "deals · crib" else " ",
            color = CribGold,
            style = tightTextStyle(11.sp, FontWeight.Bold),
            // The column is only as wide as a card, so this must not wrap — left to itself it
            // becomes two lines and shoves the Deal button down.
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DiscardArea(
    vm: GameViewModel,
    s: PlayerSnapshot,
    selected: Set<com.jirofeingold.pairfortwo.core.Card>,
    width: Dp,
    railWidth: Dp,
) {
    PlayScene(
        vm, s, railWidth,
        play = {
            HandView(
                cards = s.yourHand.sortedForDisplay(),
                selected = selected,
                onTap = { vm.toggleDiscard(it) },
                cardWidth = width,
                // Deal the cards in on a fresh hand.
                dealSignal = s.yourHand.map { it.id },
            )
        },
    ) {
        val whose = if (s.yourSeat == Seat.DEALER) "your crib" else "${vm.name(s.dealer)}'s crib"
        Button(
            onClick = { vm.confirmDiscard() },
            enabled = vm.canConfirmDiscard,
            colors = ButtonDefaults.buttonColors(
                containerColor = playerTheme(vm.colorID(s.you)).deep,
                contentColor = Color.White,
            ),
        ) {
            Text("Send 2 to $whose", textAlign = TextAlign.Center)
        }
    }
}

/** The pone lifts the deck, then the dealer turns up the cut — like an in-person cut. */
@Composable
private fun StarterCutArea(vm: GameViewModel, s: PlayerSnapshot, width: Dp, railWidth: Dp) {
    val lifted = vm.starterCutLifted
    val canTap = vm.youLiftCut || vm.youRevealStarter
    PlayScene(
        vm, s, railWidth,
        play = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (lifted) 30.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeckPile(
                    width, highlighted = canTap,
                    modifier = Modifier.tappable(enabled = canTap) {
                        if (vm.youLiftCut) vm.liftCut() else if (vm.youRevealStarter) vm.revealStarter()
                    },
                )
                // The portion the pone lifted off, set aside once the cut is made.
                if (lifted) DeckPile(width, highlighted = false, modifier = Modifier.alpha(0.8f))
            }
        },
    ) {
        // The instruction is in the rail; the deck itself is still the tap target.
        when {
            vm.youLiftCut -> Text(
                "Tap the deck to cut",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = tightTextStyle(15.sp, FontWeight.SemiBold),
            )
            vm.youRevealStarter -> Text(
                "Tap the deck to turn up the cut",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = tightTextStyle(15.sp, FontWeight.SemiBold),
            )
            else -> WaitingLabel(
                if (lifted) "Waiting for ${vm.name(s.dealer)} to turn up the cut…"
                else "Waiting for ${vm.name(s.pone)} to cut the deck…",
            )
        }
    }
}

/** A small stack of face-down cards drawn as a deck. */
@Composable
private fun DeckPile(width: Dp, highlighted: Boolean, modifier: Modifier = Modifier) {
    Box(modifier) {
        for (i in 0 until 4) {
            CardView(
                null,
                faceUp = false,
                isHighlighted = highlighted && i == 3,
                width = width,
                modifier = Modifier.offset(x = (i * 2.5f).dp, y = (i * -2.5f).dp),
            )
        }
    }
}

@Composable
private fun PeggingArea(
    vm: GameViewModel,
    s: PlayerSnapshot,
    handWidth: Dp,
    pileWidth: Dp,
    railWidth: Dp,
    uncommitted: Int,
    commitThenAdvance: () -> Unit,
    isTablet: Boolean,
) {
    PlayScene(
        vm, s, railWidth,
        play = {
            Column(
                Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // On a tablet, drop the pile down from the very top of the play area — this spacer
                // balances the two below it, so the hand still sits centred between the pile and the
                // bottom. A phone keeps the pile at the top, where the height is tight.
                if (isTablet) Spacer(Modifier.weight(1f))

                // The running count lives inside the pile, which frees this space for bigger cards.
                PlayPileView(s, colorIDFor = { vm.colorID(it) }, cardWidth = pileWidth)

                // Your hand is centred in the space between the pile and the bottom rather than
                // pinned to the bottom. The slot keeps its height even when the hand is empty, so
                // nothing shifts as you play your last card — right up until the count.
                Spacer(Modifier.weight(1f))
                Box(Modifier.height(handWidth * 1.45f), contentAlignment = Alignment.Center) {
                    if (!vm.peggingComplete && s.yourHand.isNotEmpty()) {
                        HandView(
                            cards = s.yourHand.sortedForDisplay(),
                            isEnabled = { vm.isLegalPlay(it) },
                            onTap = { vm.play(it) },
                            cardWidth = handWidth,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        },
    ) {
        if (vm.peggingComplete) {
            if (vm.youStartCount) {
                // Folding in a staged amount here matters: last-card, go and 31 points are claimed
                // during pegging, and moving to the count would otherwise strand them.
                GoldButton(
                    if (uncommitted > 0) "Add $uncommitted & count the hands" else "Count the hands",
                    commitThenAdvance,
                )
            } else {
                WaitingLabel("Waiting for ${vm.name(s.lastToPlay ?: s.you)}…")
            }
        } else if (vm.canSayGo) {
            Button(
                onClick = { vm.sayGo() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE08A2E),
                    contentColor = Color.Black,
                ),
            ) { Text("Go") }
        }
    }
}

@Composable
private fun ShowArea(
    vm: GameViewModel,
    s: PlayerSnapshot,
    pileWidth: Dp,
    railWidth: Dp,
    uncommitted: Int,
    commitThenAdvance: () -> Unit,
) {
    val isCrib = s.phase == GamePhase.SHOW_CRIB
    // The crib adds a badge and a backing, so shrink its cards a hair — no more than that, now the
    // button lives in the rail rather than below the cards.
    val cardW = if (isCrib) pileWidth * 0.92f else pileWidth
    PlayScene(
        vm, s, railWidth,
        play = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("The Cut", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    // A full card here, not the pegging pile's rank+suit tile: this one is being
                    // counted into a hand, so it should look like the cards beside it.
                    s.starter?.let { CardView(it, width = cardW) }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isCrib) {
                        // A distinct gold badge, so it is obvious the crib is being counted rather
                        // than another hand.
                        Text(
                            "${vm.name(s.dealer)}'s crib".uppercase(),
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .background(CribGold, CircleShape)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    } else {
                        Text(vm.showLabel, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                    HandView(
                        cards = vm.showCards.sortedForDisplay(),
                        onTap = {},
                        cardWidth = cardW,
                        // Re-deals on each show sub-phase, as the Swift does.
                        dealSignal = s.phase,
                    )
                }
            }
        },
    ) {
        if (vm.youAreCounting) {
            Text(
                if (s.scoringMode == ScoringMode.AUTO) "Scored automatically"
                else "Count it on your slider, then Continue",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            GoldButton(if (uncommitted > 0) "Add $uncommitted & continue" else "Continue", commitThenAdvance)
        } else {
            WaitingLabel("Waiting for ${vm.name(vm.showCountingPlayer ?: s.you)} to count…")
        }
    }
}

@Composable
private fun HandCompleteArea(vm: GameViewModel, s: PlayerSnapshot, railWidth: Dp) {
    PlayScene(
        vm, s, railWidth,
        play = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Bigger than it was: with the play area to itself this is the whole screen's
                // content, and at the old size it read as a caption.
                Text("Hand complete", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${s.yourName} ${s.yourScore}  •  ${s.opponentName} ${s.opponentScore}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }
        },
    ) {
        // The deal passes to the former pone, so only they start the next hand.
        if (vm.youStartNextDeal) {
            GoldButton("Deal next hand") { vm.advance() }
        } else {
            WaitingLabel("Waiting for ${vm.name(vm.nextDealer)} to deal…")
        }
    }
}

// ---- Shared bits ----

@Composable
private fun GoldButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = CribGold, contentColor = Color.Black),
        border = BorderStroke(0.dp, Color.Transparent),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

/** A spinner over its text, so it sits comfortably in the narrow action rail. */
@Composable
private fun WaitingLabel(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.wrapContentHeight(),
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** A tap target with no ripple, matching the Swift's `.buttonStyle(.plain)` card taps. */
@Composable
private fun Modifier.tappable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
