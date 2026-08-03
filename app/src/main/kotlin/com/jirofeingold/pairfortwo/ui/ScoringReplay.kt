package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jirofeingold.pairfortwo.core.Claim
import com.jirofeingold.pairfortwo.core.GamePhase
import com.jirofeingold.pairfortwo.core.PlayerID
import com.jirofeingold.pairfortwo.feel.GameFeedback
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.FeltMid
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme
import com.jirofeingold.pairfortwo.ui.theme.PlayerTheme
import com.jirofeingold.pairfortwo.ui.theme.playerThemes
import kotlinx.coroutines.delay

/**
 * Replays how the scores were built over the whole game — port of the iOS `ScoringReplayView`.
 *
 * It steps through every claim in order, incrementing each running total so you can watch the race
 * to 121. Shown from the win screen's "Replay scoring", or automatically *before* the win screen
 * when "Replay scoring before the win" is on.
 *
 * Paced to a snappy ~7 seconds regardless of how many scores there were, then it holds on the final
 * totals for a moment and moves on by itself. "Skip" (or "Show result" at the end) cuts the wait.
 *
 * **No watchdog, unlike iOS.** `GameTableView` arms a timer so the win screen still appears if the
 * replay's self-dismiss is interrupted — SwiftUI can re-create a view and cancel its `Task`. A
 * `LaunchedEffect` keyed on [events] doesn't have that failure mode: it is only cancelled by leaving
 * composition or by the key changing, and the score log is final once the game is over. A timer
 * guarding against something that can't happen here would be noise.
 */
@Composable
fun ScoringReplay(
    events: List<Claim>,
    nameOne: String,
    nameTwo: String,
    themeOne: PlayerTheme,
    themeTwo: PlayerTheme,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    feedback: GameFeedback? = null,
) {
    var step by remember { mutableIntStateOf(0) }
    var appeared by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(if (appeared) 1f else 0f, tween(500), label = "replayAppear")

    LaunchedEffect(events) {
        appeared = true
        // An empty log has nothing to replay; hand straight back rather than showing an empty card.
        if (events.isEmpty()) {
            onFinish()
            return@LaunchedEffect
        }
        val per = (TOTAL_MS / events.size).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
        for (i in 1..events.size) {
            delay(per)
            step = i
            // Scaled by the score, so a 12-hand lands harder than a peg — otherwise a long replay
            // is one undifferentiated string of identical taps.
            feedback?.playScoreTick(events[i - 1].amount)
        }
        delay(HOLD_MS)
        onFinish()
    }

    val current = events.getOrNull(step - 1)

    Box(
        modifier
            .background(
                Brush.verticalGradient(
                    listOf(FeltMid.copy(alpha = 0.94f), FeltDark.copy(alpha = 0.97f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp)
                .alpha(appear)
                .scale(0.94f + 0.06f * appear),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "SCORING REPLAY",
                color = Color.White.copy(alpha = 0.8f),
                style = tightTextStyle(13.sp, FontWeight.Black, letterSpacing = 3.sp),
            )

            Row(
                Modifier.widthIn(max = 640.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScoreColumn(events, step, PlayerID.ONE, nameOne, themeOne, current, Modifier.weight(1f))
                Box(
                    Modifier
                        .width(1.dp)
                        .height(64.dp)
                        .background(Color.White.copy(alpha = 0.15f)),
                )
                ScoreColumn(events, step, PlayerID.TWO, nameTwo, themeTwo, current, Modifier.weight(1f))
            }

            // What the most recent step scored.
            if (current != null) {
                Text(
                    "${current.phase.replayLabel} · +${current.amount}",
                    color = Color.White.copy(alpha = 0.85f),
                    style = tightTextStyle(14.sp, FontWeight.SemiBold),
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            } else {
                Text(
                    "The whole game, score by score",
                    color = Color.White.copy(alpha = 0.7f),
                    style = tightTextStyle(14.sp),
                )
            }

            LinearProgressIndicator(
                progress = { step.toFloat() / maxOf(events.size, 1).toFloat() },
                color = CribGold,
                trackColor = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.width(300.dp),
            )

            Text(
                if (step >= events.size) "Show result" else "Skip",
                color = Color.Black,
                style = tightTextStyle(15.sp, FontWeight.Bold),
                modifier = Modifier
                    .background(CribGold, CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        // Jump to the final totals so the numbers you leave on are the real ones.
                        step = events.size
                        onFinish()
                    }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ScoreColumn(
    events: List<Claim>,
    step: Int,
    player: PlayerID,
    name: String,
    theme: PlayerTheme,
    current: Claim?,
    modifier: Modifier = Modifier,
) {
    // The running total over the first `step` events — recomputed rather than accumulated, so
    // skipping to the end can't leave it out of step with what's on screen.
    val score = events.take(step).filter { it.player == player }.sumOf { it.amount }
    val isScoring = current?.player == player
    val emphasis by animateFloatAsState(if (isScoring) 1.06f else 1f, tween(200), label = "emphasis")

    Column(
        modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            name.uppercase(),
            color = theme.primary,
            maxLines = 1,
            textAlign = TextAlign.Center,
            style = tightTextStyle(17.sp, FontWeight.Black),
        )
        Row(
            Modifier.scale(emphasis),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$score",
                color = Color.White,
                maxLines = 1,
                style = tightTextStyle(46.sp, FontWeight.Black),
            )
            if (isScoring && current != null) {
                Text(
                    "+${current.amount}",
                    color = Color.White,
                    style = tightTextStyle(19.sp, FontWeight.Black),
                    modifier = Modifier
                        .background(theme.primary, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Where a claim came from, in the replay's shorthand. */
private val GamePhase.replayLabel: String
    get() = when (this) {
        GamePhase.PEGGING -> "Pegging"
        GamePhase.SHOW_PONE, GamePhase.SHOW_DEALER -> "Hand"
        GamePhase.SHOW_CRIB -> "Crib"
        else -> "Cut"
    }

/** The whole replay is held to about this long, however many scores there were. */
private const val TOTAL_MS = 7_000L
private const val MIN_STEP_MS = 120L
private const val MAX_STEP_MS = 500L

/** A beat on the final totals before it moves on by itself. */
private const val HOLD_MS = 1_500L

@Preview(showBackground = true, widthDp = 900, heightDp = 420)
@Composable
private fun ScoringReplayPreview() {
    PairForTwoTheme {
        ScoringReplay(
            events = listOf(
                Claim(PlayerID.ONE, 2, GamePhase.PEGGING),
                Claim(PlayerID.TWO, 1, GamePhase.PEGGING),
                Claim(PlayerID.ONE, 8, GamePhase.SHOW_PONE),
                Claim(PlayerID.TWO, 12, GamePhase.SHOW_DEALER),
                Claim(PlayerID.TWO, 4, GamePhase.SHOW_CRIB),
                Claim(PlayerID.ONE, 6, GamePhase.PEGGING),
            ),
            nameOne = "Ann",
            nameTwo = "Ben",
            themeOne = playerThemes[1],
            themeTwo = playerThemes[7],
            onFinish = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
