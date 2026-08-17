package com.jirofeingold.pairfortwo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.foundation.layout.safeContent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
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
import kotlinx.coroutines.delay
import com.jirofeingold.pairfortwo.feel.GameFeedback
import com.jirofeingold.pairfortwo.feel.HapticPatterns
import com.jirofeingold.pairfortwo.core.GamePhase
import com.jirofeingold.pairfortwo.core.GameViewModel
import com.jirofeingold.pairfortwo.core.PegEvent
import com.jirofeingold.pairfortwo.core.PlayerID
import com.jirofeingold.pairfortwo.core.PlayerSnapshot
import com.jirofeingold.pairfortwo.core.ScoreFlag
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
    // The transient "Go / 31 / last card — take the score" toast.
    var pegAlert by remember { mutableStateOf<String?>(null) }
    // "Check my count" — the correct scoring for whatever is being counted.
    var showCheck by remember { mutableStateOf(false) }

    // A fresh hand or a rematch re-arms the pre-win replay for the next game over.
    LaunchedEffect(snapshot.phase) {
        if (snapshot.phase == GamePhase.DISCARD_TO_CRIB) {
            preWinReplayShown = false
            showManualReplay = false
        }
    }

    // The game was quit — by this player or the other one — so hand back to whoever presented us.
    LaunchedEffect(ended) { if (ended) onExit?.invoke() }

    // Hitting exactly 31 is the one moment in pegging with no button behind it — it falls out of
    // the count — so it is felt here rather than at a call site.
    LaunchedEffect(snapshot.runningCount) {
        if (snapshot.runningCount == 31) feedback?.play(HapticPatterns.Action.THIRTY_ONE)
    }

    // A go, a 31 or the hand's last card: tell the player who earns the point to take it, and the
    // other player why the play stopped. Keyed on the engine's tick, not the event itself, so a
    // repeat (heartbeat) broadcast of the same snapshot never re-fires the toast.
    //
    // Android had none of this — the events were on the snapshot and nothing consumed them, so a
    // "go" from the other device was completely silent.
    LaunchedEffect(vm.pegEventTick) {
        val event = vm.lastPegEvent
        if (vm.pegEventTick == 0 || event == null) return@LaunchedEffect
        val auto = snapshot.scoringMode == ScoringMode.AUTO
        val mine = event.scorer == snapshot.you || vm.isLoopback
        val who = vm.name(event.scorer)
        val text = when (event.kind) {
            PegEvent.Kind.GO ->
                if (event.points == 0) {
                    // A go that scores nothing passed the play to the other player — only they need
                    // telling, and telling the sayer "your play" would be a lie.
                    if (mine) null else "$who said Go — your play"
                } else if (auto) {
                    "Go — $who pegs 1"
                } else if (mine) {
                    "Go — take 1"
                } else {
                    "$who takes 1 for the go"
                }
            PegEvent.Kind.THIRTY_ONE ->
                if (auto) "31 for ${event.points}!"
                else if (mine) "31 — take ${event.points}"
                else "$who hits 31 for ${event.points}"
            PegEvent.Kind.LAST_CARD ->
                if (auto) "Last card — $who pegs ${event.points}"
                else if (mine) "Last card — take ${event.points}"
                else "Last card played — $who takes ${event.points}"
        } ?: return@LaunchedEffect

        feedback?.play(
            if (event.kind == PegEvent.Kind.THIRTY_ONE) {
                HapticPatterns.Action.THIRTY_ONE
            } else {
                HapticPatterns.Action.GO
            },
        )
        pegAlert = text
        delay(PEG_ALERT_MS)
        pegAlert = null
    }

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
        feedback?.play(HapticPatterns.Action.ADVANCE)
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
    val insetTop = safeInsets.calculateTopPadding()
    val insetBottom = safeInsets.calculateBottomPadding()

    // **Horizontal insets are applied symmetrically**, using the larger of the two sides.
    //
    // A landscape phone puts its selfie camera on one side only — 47dp of cutout on the left of the
    // test device and nothing on the right. Padding each side by its own inset clears the camera but
    // shifts everything 47dp right, and the scoreboard visibly stops looking centred. Since the
    // table is a symmetric composition, matching the sides costs a few dp of felt on the clear side
    // and buys a layout that is centred *on the screen*, which is what the eye actually checks.
    val insetSide = maxOf(
        safeInsets.calculateLeftPadding(layoutDirection),
        safeInsets.calculateRightPadding(layoutDirection),
    )

    // Where the system will take a gesture no matter what the app asks. `safeContent` is
    // `safeDrawing` plus the gesture strips, and is what every other screen pads by wholesale; the
    // table can't afford that — the strips are 43dp of its height — so only the controls use it.
    //
    // The floating corner controls have to clear these or they are simply hard to press: the strips
    // are *mandatory*, so `systemGestureExclusion()` is ignored inside them and a tap that drifts a
    // pixel is read as a system swipe. On the test device that is the top 43dp (swipe down for the
    // hidden status bar) and the right 48dp (the navigation bar's edge) — which is exactly where
    // help and settings were sitting.
    val gestureInsets = WindowInsets.safeContent.asPaddingValues()
    val controlInsetTop = maxOf(insetTop, gestureInsets.calculateTopPadding())
    val controlInsetSide = maxOf(
        insetSide,
        gestureInsets.calculateLeftPadding(layoutDirection),
        gestureInsets.calculateRightPadding(layoutDirection),
    )

    CompositionLocalProvider(LocalFeedback provides feedback) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeltMid, FeltDark))),
    ) {
        // Budgets are computed from the *usable* area, so hiding the insets from the backgrounds
        // doesn't quietly hand the cards space they can't actually occupy.
        val height = maxHeight - insetTop - insetBottom
        val width = maxWidth - insetSide * 2

        // The band is exactly as tall as its content needs — see [TOP_BAND_HEIGHT] — rather than a
        // fraction of the screen. It used to take 42% (capped at 215dp) and spend the surplus on
        // whitespace above the scoreboard, which is height the cards want far more.
        val topBandHeight = TOP_BAND_HEIGHT
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
        // Pegging is budgeted on its own terms rather than inheriting the discard hand's width.
        // Its hand is at most four cards, not six, so dividing the column by seven left them far
        // narrower than the space allowed; and the vertical stack is a half-height pile, one 8dp
        // gap and the hand — 2.175x the card width — so reserving 44dp for it was ~36dp of nothing.
        // The vertical stack, measured rather than guessed: 28dp of column padding, a ~24dp label
        // row over the pile ("The Cut" / "Count" / "Crib"), an 8dp gap, then the pile card at
        // 0.45x and the hand card at 1x — 1.45x their widths in height. 60dp of fixed overhead and
        // a 2.11 ratio. The label row is what the old 2.15/44dp budget missed, which is why the
        // hand hung off the bottom the moment the play area got taller.
        val peggingHandWidth = minOf((playWidth - 40.dp) / 4.6f, (playHeight - 66.dp) / 1.96f)
        val pileWidth = peggingHandWidth * 0.35f
        // The show row is the cut card, a 16dp gap, then a four-card hand — but `HandView` spaces
        // its cards by 0.18x their width, so the row is 5.54 card-widths, not five.
        //
        // Dividing by five overflowed the column by half a card, and a Row hands the shortfall to
        // its last child: the fifth card came out visibly narrower than the other four. Budgeting
        // the gaps honestly keeps all five identical.
        val showWidth = minOf((playWidth - 30.dp) / 5.6f, (playHeight - 40.dp) / 1.45f)
        // The two cut screens hold just two cards, so they'd balloon on a tablet — halve them there.
        // 130dp, not 76: the cut card in the rail carries a "Tap to cut" caption under it, and the
        // budget has to cover the caption as well as the card or the caption is the thing that gets
        // clipped off the bottom — silently, because Compose drops an overflowing child rather than
        // scrolling it. Checked against the shortest layout that has to work: a 411dp-tall emulator,
        // which is tighter than the 443dp test phone. 148dp, not 130: at 130 the caption's
        // descenders sat on the bottom edge of the test phone once the play area got its height
        // back.
        val cutBase = minOf((playWidth - 50.dp) / 2.2f, (playHeight - 148.dp) / 1.45f)
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
                onQuit = if (onExit != null) ({ showQuitConfirm = true }) else null,
                onOpenHelp = onOpenHelp,
                onOpenSettings = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    // The band's own height plus whatever sits above it, so the dark strip runs
                    // edge to edge and up under a cutout rather than stopping short of it.
                    //
                    // The band is deliberately *not* pushed down by the controls' gesture inset.
                    // It was, briefly, and it cost the play area 43dp on the test phone — the cards
                    // visibly shrank. Only the controls move; the band just reserves a taller first
                    // row for them, which its own slack absorbs.
                    .heightIn(max = topBandHeight + insetTop)
                    .background(Color.Black.copy(alpha = 0.22f))
                    // Padding, not `windowInsetsPadding`, because the sides are matched rather than
                    // taken per-side — see [insetSide]. The background is applied first either way,
                    // so the dark strip still bleeds to the screen edge.
                    .padding(top = insetTop, start = controlInsetSide, end = controlInsetSide)
                    .clipToBounds(),
            )
            BottomBand(
                vm = vm,
                s = snapshot,
                selected = selected,
                uncommitted = uncommitted.values.sum(),
                commitThenAdvance = commitThenAdvance,
                onCheckCount = { showCheck = true },
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
                    // The bottom edge is a system gesture strip on a gesture-navigation phone,
                    // and the hand you tap to play sits on it, so this clears the gesture inset
                    // rather than just the drawing one. Zero on the test device, where the
                    // navigation bar is down the side instead.
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = controlInsetSide)
                    .padding(vertical = 14.dp),
            )
        }

        pegAlert?.let { text ->
            Text(
                text,
                color = Color.Black,
                textAlign = TextAlign.Center,
                style = tightTextStyle(17.sp, FontWeight.Black),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Below the band, so it never covers the coach line — same place as iOS.
                    .padding(top = TOP_BAND_HEIGHT + insetTop + 12.dp)
                    .background(CribGold, CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        // The corner controls are no longer floated over the band — they sit *in* it, on the
        // scoreboard row. See [TopBand].
        if (onExit != null) BackHandler(enabled = !showQuitConfirm) { showQuitConfirm = true }

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

        if (showCheck) {
            CheckCountOverlay(
                label = vm.showLabel,
                flags = vm.checkScoreFlags,
                total = vm.checkScoreTotal,
                onDismiss = { showCheck = false },
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

/** The drawn disc, and the touch area around it. See [ControlButton]. */
/**
 * The game's sound and haptics, ambient for the table.
 *
 * Every phase's play area is its own private composable several levels down, and each of them has
 * an action worth feeling — a cut, a card, a go. Threading one handle through all of them would be
 * noise at every signature for a value that is the same everywhere on screen, so it rides the
 * composition the way [LocalCardBackID] does.
 */
private val LocalFeedback = staticCompositionLocalOf<GameFeedback?> { null }

/** One line of coach banner. */
private val BANNER_ROW_HEIGHT = 26.dp

/** The scoreboard row: the score panels, and the chrome controls sitting level with them. */
private val SCORE_PANEL_HEIGHT = 92.dp

/**
 * The top band's whole height, and so the play area's budget.
 *
 * Stated rather than derived from the screen: 12dp of padding, the coach line, an 8dp gap, the
 * scoreboard row and 4dp below it. Every dp not spent here is a dp the cards get.
 */
private val TOP_BAND_HEIGHT = 12.dp + BANNER_ROW_HEIGHT + 8.dp + SCORE_PANEL_HEIGHT + 4.dp

/** How long the go / 31 / last-card toast stays up. iOS uses the same couple of seconds. */
private const val PEG_ALERT_MS = 2_400L

private val CONTROL_DISC = 36.dp
private val CONTROL_TARGET = 48.dp

/** "Check" — the show rail's gold-outlined pill, as on iOS. */
@Composable
private fun CheckPill(onClick: () -> Unit) {
    Row(
        Modifier
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .border(1.2.dp, CribGold.copy(alpha = 0.6f), CircleShape)
            .clickable(
                role = Role.Button,
                onClickLabel = "Check my count",
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = CribGold,
            modifier = Modifier.size(16.dp),
        )
        Text("Check", color = CribGold, style = tightTextStyle(14.sp, FontWeight.SemiBold))
    }
}

/**
 * The correct count for whatever is being counted — port of iOS's check-my-count overlay.
 *
 * The point of a manual scoring mode is that you do the arithmetic; the point of this is that you
 * can find out whether you got it right without the app having done it for you first. It lists the
 * same breakdown the scorer would apply, so a disagreement is legible rather than a bare number.
 */
@Composable
private fun CheckCountOverlay(
    label: String,
    flags: List<ScoreFlag>,
    total: Int,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .windowInsetsPadding(WindowInsets.safeContent)
                .background(FeltMid, RoundedCornerShape(20.dp))
                .border(1.dp, CribGold.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                // Swallow taps on the card itself, so only the scrim dismisses.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Correct count", color = Color.White, style = tightTextStyle(19.sp, FontWeight.Bold))
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)

            if (flags.isEmpty()) {
                Text("0", color = CribGold, style = tightTextStyle(42.sp, FontWeight.Black))
                Text(
                    "Nothing scores in this hand.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 150.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (flag in flags) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                flag.detail,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 14.sp,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "+${flag.points}",
                                color = CribGold,
                                style = tightTextStyle(14.sp, FontWeight.Black),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.15f)),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text("$total", color = CribGold, style = tightTextStyle(40.sp, FontWeight.Black))
                    Text(
                        if (total == 1) "point" else "points",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }

            GoldButton("Got it", onDismiss)
        }
    }
}

/**
 * The table's own chrome button — iOS's `controlButton`, sized for a thumb.
 *
 * **Deliberate divergence from iOS**, which draws a 32pt disc. Three things made that too small
 * here, and they compound in exactly the corner these buttons live in:
 *
 * - The clickable was the *drawn* size. `minimumInteractiveComponentSize` reserves 48dp of layout
 *   and relies on out-of-bounds pointer interception to feed the smaller child; the target now
 *   simply *is* 56dp, with the disc drawn inside it, which needs no such trust.
 * - The app runs fullscreen with swipe-to-reveal bars, so these sit on the screen edge where the
 *   system watches for its own gestures. [systemGestureExclusion] claims the small region back —
 *   without it a tap that drifts a pixel is read as an edge swipe and the button never fires.
 * - A landscape phone held two-handed puts these under the very tip of a thumb, at the point where
 *   accuracy is worst.
 */
@Composable
private fun ControlButton(
    onClick: () -> Unit,
    description: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(CONTROL_TARGET)
            .systemGestureExclusion()
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                // A ripple, where the rest of the table's controls draw their own feedback. With no
                // indication at all a tap that *did* land looks identical to one that didn't, so a
                // missed press and a slow screen are indistinguishable — and you tap again.
                indication = ripple(bounded = false, radius = CONTROL_TARGET / 2),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(CONTROL_DISC)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
        }
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
    onQuit: (() -> Unit)?,
    onOpenHelp: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Just the coach line, at the very top of the band. It used to sit in a row tall enough to
        // reserve space for the floating corner icons, which is where the whitespace above the
        // scoreboard came from; the icons are on the scoreboard row now and this is back to being
        // one line of text.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = BANNER_ROW_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                vm.coachBanner,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = tightTextStyle(17.sp, FontWeight.Bold),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }

        // The scoring flags ("Fifteen 2 +2" …) used to sit here. They live in the play area's action
        // rail now, so this dark band holds only the coach line and the scoreboard — which is what
        // gives the scores room to be read across a table.

        // Quit on one end, help and settings on the other, level with the scoreboard.
        //
        // They were floating in the band's top corners, which put them inside the system's
        // mandatory top gesture strip — 43dp on the test phone — where presses are arbitrated away.
        // Insetting them past it worked but pushed the whole band down and cost the cards that
        // height. Down here they are clear of the strip for free: the scoreboard row starts well
        // below it, and the band goes back to being as short as its content.
        Row(
            Modifier.fillMaxWidth().height(SCORE_PANEL_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (onQuit != null) {
            ControlButton(onQuit, "Quit game", Icons.Filled.Close)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        if (s.scoringMode == ScoringMode.AUTO) {
            // Automatic scoring has no manual controls — just names and scores.
            AutoScoreboard(vm, s)
        } else {
            // A panel per peg this device may score: both in pass-and-play, only the local
            // player's when networked. Capped so a lone panel doesn't stretch across a tablet.
            Row(
                Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
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
                            .fillMaxHeight(),
                    )
                }
            }
        }
        }
        if (onOpenHelp != null) {
            ControlButton(onOpenHelp, "How to play", Icons.AutoMirrored.Filled.HelpOutline)
        }
        if (onOpenSettings != null) {
            ControlButton(onOpenSettings, "Settings", Icons.Filled.Settings)
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
    onCheckCount: () -> Unit,
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
                ShowArea(vm, s, showWidth, railWidth, uncommitted, commitThenAdvance, onCheckCount)
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
            // Reserved only in the phases that actually surface flags. Reserving it everywhere
            // pushed the tall "tap to cut" card down off the bottom of the felt — the same fix iOS
            // made in 592343f. Within one of those phases the height is constant, so the prompt and
            // button below never shift as flags come and go.
            if (s.phase.surfacesScoreFlags || s.flags.isNotEmpty()) {
                Box(Modifier.height(FLAG_BAND_HEIGHT)) {
                    RailFlags(vm, s, Modifier.align(Alignment.TopCenter))
                }
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
    val feedback = LocalFeedback.current
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
                if (vm.youDeal) GoldButton("Deal") { feedback?.play(HapticPatterns.Action.DEAL); vm.advance() }
                else WaitingLabel("Waiting for ${vm.name(s.dealer)} to deal…")
            vm.youNeedToCut -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.tappable { feedback?.play(HapticPatterns.Action.CUT_TAP); vm.cut() },
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
    val feedback = LocalFeedback.current
    PlayScene(
        vm, s, railWidth,
        play = {
            HandView(
                cards = s.yourHand.sortedForDisplay(),
                selected = selected,
                onTap = { feedback?.play(HapticPatterns.Action.DISCARD_SELECT); vm.toggleDiscard(it) },
                cardWidth = width,
                // Deal the cards in on a fresh hand.
                dealSignal = s.yourHand.map { it.id },
            )
        },
    ) {
        val whose = if (s.yourSeat == Seat.DEALER) "your crib" else "${vm.name(s.dealer)}'s crib"
        Button(
            onClick = { feedback?.play(HapticPatterns.Action.DISCARD_CONFIRM); vm.confirmDiscard() },
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
    val feedback = LocalFeedback.current
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
                        if (vm.youLiftCut) {
                            feedback?.play(HapticPatterns.Action.DECK_LIFT)
                            vm.liftCut()
                        } else if (vm.youRevealStarter) {
                            feedback?.play(HapticPatterns.Action.STARTER_REVEAL)
                            vm.revealStarter()
                        }
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
    // The slow breathing pulse iOS gives a tappable deck: easeInOut over 0.8s, autoreversing.
    val transition = rememberInfiniteTransition(label = "deck")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "deckPulse",
    )

    // The box is sized to the *whole* stack, offsets included, rather than to one card with the
    // rest spilling over the edge. Overflow drew fine on its own but was sliced off the top and
    // right of the lifted half of the deck, which carries `Modifier.alpha` — an alpha layer clips
    // to its layout bounds, and the spill was outside them. Sizing it honestly also means the tap
    // target is the deck you can see.
    val spread = DECK_STACK_STEP * (DECK_STACK_CARDS - 1)
    Box(
        modifier
            .size(width + spread, width * 1.45f + spread)
            .scale(if (highlighted) pulse else 1f),
    ) {
        for (i in 0 until DECK_STACK_CARDS) {
            CardView(
                null,
                faceUp = false,
                isHighlighted = highlighted && i == DECK_STACK_CARDS - 1,
                width = width,
                modifier = Modifier.offset(
                    x = DECK_STACK_STEP * i,
                    y = spread - DECK_STACK_STEP * i,
                ),
            )
        }
    }
}

/** How the deck is drawn: four cards, each stepped up and to the right of the one below. */
private const val DECK_STACK_CARDS = 4
private val DECK_STACK_STEP = 2.5.dp

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
    val feedback = LocalFeedback.current
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
                            onTap = { feedback?.play(HapticPatterns.Action.CARD_PLAY); vm.play(it) },
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
                onClick = { feedback?.play(HapticPatterns.Action.GO); vm.sayGo() },
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
    onCheckCount: () -> Unit,
) {
    val feedback = LocalFeedback.current
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
                    // The crib counts inside a gold-tinted, gold-stroked panel — the same backing
                    // iOS draws. With only the badge above them, four cards on felt look like just
                    // another hand at exactly the moment it matters that they are not.
                    Box(
                        if (isCrib) {
                            Modifier
                                .background(
                                    CribGold.copy(alpha = 0.12f),
                                    RoundedCornerShape(12.dp),
                                )
                                .border(
                                    1.dp,
                                    CribGold.copy(alpha = 0.55f),
                                    RoundedCornerShape(12.dp),
                                )
                                .padding(5.dp)
                        } else {
                            Modifier
                        },
                    ) {
                        HandView(
                            cards = vm.showCards.sortedForDisplay(),
                            onTap = {},
                            cardWidth = cardW,
                            // Re-deals on each show sub-phase, as the Swift does.
                            dealSignal = s.phase,
                            // In the crib, each card is marked in the colour of whoever discarded
                            // it — four cards from two hands, and it settles "whose five was that?"
                            marker = { card ->
                                val owner = if (isCrib) vm.cribOwner(card) else null
                                owner?.let { playerTheme(vm.colorID(it)).primary }
                            },
                        )
                    }
                }
            }
        },
    ) {
        if (vm.youAreCounting) {
            // Once points are staged the button itself says what will happen ("Add 15 & continue"),
            // so the standing instruction is dropped — it would only crowd the narrow rail.
            if (uncommitted == 0) {
                Text(
                    if (s.scoringMode == ScoringMode.AUTO) "Scored automatically"
                    else "Count it on your slider, then Continue",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
            GoldButton(if (uncommitted > 0) "Add $uncommitted & continue" else "Continue", commitThenAdvance)
            // Nothing to check when the app is doing the counting.
            if (s.scoringMode != ScoringMode.AUTO) {
                CheckPill { feedback?.play(HapticPatterns.Action.ADVANCE); onCheckCount() }
            }
        } else {
            WaitingLabel("Waiting for ${vm.name(vm.showCountingPlayer ?: s.you)} to count…")
        }
    }
}

@Composable
private fun HandCompleteArea(vm: GameViewModel, s: PlayerSnapshot, railWidth: Dp) {
    val feedback = LocalFeedback.current
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
            GoldButton("Deal next hand") { feedback?.play(HapticPatterns.Action.DEAL); vm.advance() }
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
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Wraps rather than truncating: the rail is a fixed width and these labels carry player
        // names ("Add 15 & count the hands"), so one line is not a safe assumption.
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
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
