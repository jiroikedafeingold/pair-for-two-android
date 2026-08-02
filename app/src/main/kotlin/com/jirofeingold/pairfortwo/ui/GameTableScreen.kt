package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * - **`ScorePanel`** — the manual peg slider, ~500 lines of custom drawing in the Swift and a piece
 *   of work in its own right. Until it lands, the manual scoring modes show a plain readout, and
 *   [ScoringMode.AUTO] is fully playable.
 * - The winner, loser, replay and check-my-count overlays.
 * - Sound and haptics, which exist (`feel/`) but aren't wired to these call sites yet.
 */
@Composable
fun GameTableScreen(
    vm: GameViewModel,
    modifier: Modifier = Modifier,
    feedback: GameFeedback? = null,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val selected by vm.selectedForDiscard.collectAsStateWithLifecycle()

    // Points staged on the local panel but not yet claimed. Continue folds them in, so a last-card
    // or go point can't be stranded by moving the game on — the same reason iOS tracks it.
    var uncommitted by remember { mutableIntStateOf(0) }
    var clearSignal by remember { mutableIntStateOf(0) }
    val commitThenAdvance: () -> Unit = {
        if (uncommitted > 0) {
            vm.claim(uncommitted, snapshot.you)
            clearSignal += 1
            uncommitted = 0
        }
        vm.advance()
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeltMid, FeltDark))),
    ) {
        val height = maxHeight
        val width = maxWidth

        // The same budget the Swift computes. Capping the band stops it leaving a tall dead zone on
        // a tablet; the play area takes whatever is left.
        val topBandHeight = minOf(height * 0.40f, 200.dp)
        // Card budgets still assume the band takes its full cap, so a band that wraps smaller only
        // ever leaves *more* room than the cards were sized for — never less.
        val playHeight = height - topBandHeight

        // The discard shows a full six-card hand and nothing else, so those cards can be large.
        // Pegging must stack a pile above the hand, so its cards are clamped to the shorter budget.
        val handWidth = minOf((width - 40.dp) / 7f, (playHeight - 60.dp) / 1.55f)
        val peggingHandWidth = minOf(handWidth, (playHeight - 44.dp) / 2.15f)
        val pileWidth = peggingHandWidth * 0.5f
        val showWidth = handWidth * 0.66f
        // Cut-for-deal stacks two card groups vertically — results on top, the tap target below —
        // so it is sized off the play area rather than the width. The Swift uses 0.24 here; on
        // Android the labels and the button measure a little taller, and at 0.24 the group overflows
        // and Compose drops the "Tap to cut" caption entirely. 0.20 leaves it room.
        val cutWidth = minOf(handWidth * 0.6f, playHeight * 0.18f)

        Column(Modifier.fillMaxSize()) {
            // The Swift pins this band to a fixed height and clips. Here it sizes to its content
            // up to the same cap instead: Android's text measures a little taller, and at a fixed
            // height the scoreboard's digits were sliced off the moment the flag chips appeared.
            // Wrapping also means the band is shorter when there are no flags, which hands the play
            // area more room rather than leaving a dead strip.
            TopBand(
                vm = vm,
                s = snapshot,
                feedback = feedback,
                clearSignal = clearSignal,
                onUncommittedChange = { uncommitted = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = topBandHeight)
                    .background(Color.Black.copy(alpha = 0.22f))
                    .clipToBounds(),
            )
            BottomBand(
                vm = vm,
                s = snapshot,
                selected = selected,
                uncommitted = uncommitted,
                commitThenAdvance = commitThenAdvance,
                handWidth = handWidth,
                peggingHandWidth = peggingHandWidth,
                pileWidth = pileWidth,
                showWidth = showWidth,
                cutWidth = cutWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 14.dp),
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
    clearSignal: Int,
    onUncommittedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
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

        val scoringPlayer = vm.scoringPlayer
        ScoreFlagsView(
            flags = s.flags,
            accent = scoringPlayer?.let { playerTheme(vm.colorID(it)).primary } ?: CribGold,
            playerName = scoringPlayer?.let { vm.name(it) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

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
                    val isLocal = player == s.you
                    ScorePanel(
                        name = vm.name(player),
                        score = vm.score(player),
                        opponentScore = vm.score(player.opponent),
                        primary = theme.primary,
                        deep = theme.deep,
                        disabled = s.phase == GamePhase.GAME_OVER || vm.scoringDisabled(player),
                        canUndo = vm.canUndo(player),
                        // iOS passes `isLocal ? confirmRelease : false`, and confirmRelease
                        // defaults to true — so releasing the slider *stages* an amount and the
                        // +N button commits it, rather than scoring the instant a thumb lifts.
                        // Becomes a real setting when SettingsScreen lands.
                        requireConfirm = isLocal,
                        opponentColor = playerTheme(vm.colorID(player.opponent)).primary,
                        showOpponentTrack = vm.scorablePlayers.size == 1,
                        onUncommittedChange = { if (isLocal) onUncommittedChange(it) },
                        clearSignal = if (isLocal) clearSignal else 0,
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
            .widthIn(max = 700.dp)
            .padding(horizontal = 34.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreColumn(vm, s.you, Modifier.weight(1f))
        Box(
            Modifier
                .width(1.dp)
                .height(36.dp)
                .background(Color.White.copy(alpha = 0.15f)),
        )
        ScoreColumn(vm, s.you.opponent, Modifier.weight(1f))
    }
}

@Composable
private fun ScoreColumn(vm: GameViewModel, player: PlayerID, modifier: Modifier = Modifier) {
    val theme = playerTheme(vm.colorID(player))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            vm.name(player).uppercase(),
            color = theme.primary,
            maxLines = 1,
            style = tightTextStyle(14.sp, FontWeight.Black),
        )
        Text(
            "${vm.score(player)}",
            color = Color.White,
            maxLines = 1,
            style = tightTextStyle(40.sp, FontWeight.Black),
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
    handWidth: Dp,
    peggingHandWidth: Dp,
    pileWidth: Dp,
    showWidth: Dp,
    cutWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (s.phase) {
            GamePhase.CUT_FOR_DEAL -> CutForDealArea(vm, s, cutWidth)
            GamePhase.DISCARD_TO_CRIB -> DiscardArea(vm, s, selected, handWidth)
            GamePhase.CUT_STARTER -> StarterCutArea(vm, s, cutWidth)
            GamePhase.PEGGING ->
                PeggingArea(vm, s, peggingHandWidth, pileWidth, uncommitted, commitThenAdvance)
            GamePhase.SHOW_PONE, GamePhase.SHOW_DEALER, GamePhase.SHOW_CRIB ->
                ShowArea(vm, s, showWidth, uncommitted, commitThenAdvance)
            GamePhase.HAND_COMPLETE -> HandCompleteArea(vm, s)
            GamePhase.GAME_OVER -> GameOverArea(vm, s)
            else -> Spacer(Modifier.fillMaxSize())
        }
    }
}

/**
 * Each player cuts once and their card is shown to both. Once both have cut, the lower card wins the
 * deal and the first crib; the dealer then taps Deal.
 */
@Composable
private fun CutForDealArea(vm: GameViewModel, s: PlayerSnapshot, width: Dp) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
            CutResult(vm, s, PlayerID.ONE, width)
            CutResult(vm, s, PlayerID.TWO, width)
        }

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
                Text("Tap to cut", color = Color.White, style = tightTextStyle(15.sp, FontWeight.SemiBold))
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
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        HandView(
            cards = s.yourHand.sortedForDisplay(),
            selected = selected,
            onTap = { vm.toggleDiscard(it) },
            cardWidth = width,
            // Deal the cards in on a fresh hand.
            dealSignal = s.yourHand.map { it.id },
        )
        val whose = if (s.yourSeat == Seat.DEALER) "your crib" else "${vm.name(s.dealer)}'s crib"
        Button(
            onClick = { vm.confirmDiscard() },
            enabled = vm.canConfirmDiscard,
            colors = ButtonDefaults.buttonColors(
                containerColor = playerTheme(vm.colorID(s.you)).deep,
                contentColor = Color.White,
            ),
        ) {
            Text("Send 2 to $whose")
        }
    }
}

/** The pone lifts the deck, then the dealer turns up the cut — like an in-person cut. */
@Composable
private fun StarterCutArea(vm: GameViewModel, s: PlayerSnapshot, width: Dp) {
    val lifted = vm.starterCutLifted
    val canTap = vm.youLiftCut || vm.youRevealStarter
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
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

        // The instruction sits under the deck — the deck itself is the tap target.
        when {
            vm.youLiftCut -> Text(
                "Tap the deck to cut",
                color = Color.White, style = tightTextStyle(15.sp, FontWeight.SemiBold),
            )
            vm.youRevealStarter -> Text(
                "Tap the deck to turn up the cut",
                color = Color.White, style = tightTextStyle(15.sp, FontWeight.SemiBold),
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
    uncommitted: Int,
    commitThenAdvance: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PlayPileView(s, colorIDFor = { vm.colorID(it) }, cardWidth = pileWidth)
        }

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
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HandView(
                    cards = s.yourHand.sortedForDisplay(),
                    isEnabled = { vm.isLegalPlay(it) },
                    onTap = { vm.play(it) },
                    cardWidth = handWidth,
                )
                if (vm.canSayGo) {
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
    }
}

@Composable
private fun ShowArea(
    vm: GameViewModel,
    s: PlayerSnapshot,
    pileWidth: Dp,
    uncommitted: Int,
    commitThenAdvance: () -> Unit,
) {
    val isCrib = s.phase == GamePhase.SHOW_CRIB
    // The crib adds a badge, so shrink its cards a touch to keep the group and the button on screen.
    val cardW = if (isCrib) pileWidth * 0.8f else pileWidth
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("The Cut", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
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
                            .background(CribGold, androidx.compose.foundation.shape.CircleShape)
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

        if (vm.youAreCounting) {
            Text(
                if (s.scoringMode == ScoringMode.AUTO) "Scored automatically"
                else "Count it on your slider, then Continue",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            GoldButton(if (uncommitted > 0) "Add $uncommitted & continue" else "Continue", commitThenAdvance)
        } else {
            WaitingLabel("Waiting for ${vm.name(vm.showCountingPlayer ?: s.you)} to count…")
        }
    }
}

@Composable
private fun HandCompleteArea(vm: GameViewModel, s: PlayerSnapshot) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text("Hand complete", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "${s.yourName} ${s.yourScore}  •  ${s.opponentName} ${s.opponentScore}",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 17.sp,
        )
        // The deal passes to the former pone, so only they start the next hand.
        if (vm.youStartNextDeal) {
            GoldButton("Deal next hand") { vm.advance() }
        } else {
            WaitingLabel("Waiting for ${vm.name(vm.nextDealer)} to deal…")
        }
    }
}

/** A stand-in until `WinnerOverlay` and `LoserOverlay` are ported. */
@Composable
private fun GameOverArea(vm: GameViewModel, s: PlayerSnapshot) {
    val info = vm.winnerInfo
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text(
            when (info?.second) {
                com.jirofeingold.pairfortwo.core.SkunkLevel.DOUBLE -> "DOUBLE SKUNK!"
                com.jirofeingold.pairfortwo.core.SkunkLevel.SINGLE -> "SKUNKED!"
                else -> "VICTORY"
            },
            color = CribGold,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
        )
        Text(vm.coachBanner, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "${s.yourName} ${s.yourScore}  •  ${s.opponentName} ${s.opponentScore}",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
        )
        GoldButton("Play again") { vm.playAgain() }
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

@Composable
private fun WaitingLabel(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.wrapContentHeight(),
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Text(text, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
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
