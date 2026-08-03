package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The manual scoring control — port of the iOS `ScorePanel`.
 *
 * A 0–29 points slider, an accumulating +1 / +N button, and undo. Behind the controls sits a live
 * `your-score / opponent-score` readout in the two players' colours, and the panel's rounded edge
 * doubles as a cribbage track (see [ScoreTrackOverlay]).
 *
 * The two scoring gestures are deliberately different:
 * - **+1** always adds immediately. The number on the button is a fading count of the current
 *   streak, so tapping four times reads "+4" while still having added four separate points.
 * - **The slider** stages a value. With [requireConfirm] it waits for the button to be pressed;
 *   without it, releasing the drag adds the amount straight away.
 */
@Composable
fun ScorePanel(
    name: String,
    score: Int,
    opponentScore: Int,
    primary: Color,
    deep: Color,
    disabled: Boolean,
    canUndo: Boolean,
    onAdd: (Int) -> Unit,
    onPlusOne: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
    requireConfirm: Boolean = false,
    opponentColor: Color = Color.Gray,
    opponentPending: Int = 0,
    /**
     * True when this is the only panel on screen (networked play), so it carries *both* players'
     * loops — yours on the outer edge, the opponent's just inside. In pass-and-play there is a
     * panel each, and each shows only its own.
     */
    showOpponentTrack: Boolean = false,
    showScoreTrack: Boolean = true,
    /** Reports the amount staged but not yet added, so the screen can fold it into "Continue". */
    onUncommittedChange: (Int) -> Unit = {},
    /** When this changes, drop any staged amount — the points were claimed elsewhere. */
    clearSignal: Int = 0,
    onTick: (Double) -> Unit = {},
    onPlusHaptic: () -> Unit = {},
    onCommitHaptic: () -> Unit = {},
) {
    var pending by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var plusPending by remember { mutableIntStateOf(0) }

    val awaitingConfirm = requireConfirm && pending > 0 && !isDragging
    val displayValue = when {
        isDragging || awaitingConfirm -> pending
        plusPending > 0 -> plusPending
        else -> 1
    }
    val highlighted = displayValue != 1 || awaitingConfirm

    LaunchedEffect(pending, requireConfirm) {
        onUncommittedChange(if (requireConfirm) pending else 0)
    }

    LaunchedEffect(clearSignal) {
        if (clearSignal != 0) {
            pending = 0
            plusPending = 0
        }
    }

    // The +1 streak count fades out two seconds after the last tap.
    LaunchedEffect(plusPending) {
        if (plusPending > 0) {
            delay(2_000)
            plusPending = 0
        }
    }

    Box(
        modifier
            // One spoken summary for the whole panel. Without it a screen reader announces a pile
            // of unlabelled shapes: the readout, the ring and the track are all decoration around
            // three controls, and only the controls below are worth stopping on.
            .semantics(mergeDescendants = false) {
                contentDescription = "$name, $score points. Opponent $opponentScore."
            }
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.02f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
    ) {
        // The live readout, behind the controls: your score in your colour, theirs in theirs.
        ScoreReadout(
            score, opponentScore, primary, opponentColor, opponentPending,
            Modifier.align(Alignment.Center),
        )

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlusButton(
                displayValue = displayValue,
                // What the tap will actually do, which is not the same in the two modes: staged,
                // it commits the amount; otherwise it adds a single point.
                clickLabel = if (awaitingConfirm) "Add $pending points" else "Add one point",
                highlighted = highlighted,
                primary = primary,
                deep = deep,
                enabled = !disabled,
                onClick = {
                    if (awaitingConfirm) {
                        onAdd(pending)
                        pending = 0
                        onCommitHaptic()
                    } else if (!isDragging) {
                        onPlusHaptic()
                        onPlusOne()
                        plusPending += 1
                    }
                },
            )

            PointsSlider(
                value = pending,
                onValueChange = { pending = it },
                onCommit = { committed ->
                    if (!requireConfirm) {
                        onAdd(committed)
                        onCommitHaptic()
                        pending = 0
                    }
                },
                isDragging = isDragging,
                onDraggingChange = { isDragging = it },
                primary = primary,
                deep = deep,
                enabled = !disabled,
                onTick = onTick,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .alpha(if (disabled) 0.4f else 1f),
            )

            UndoButton(
                enabled = !disabled && (canUndo || pending > 0),
                onClick = {
                    // Undo clears a staged amount first; only once nothing is staged does it take
                    // back a claim that has already landed.
                    if (pending > 0) pending = 0 else onUndo()
                },
            )
        }

        if (showScoreTrack) {
            ScoreTrackOverlay(
                youFraction = loopFraction(score),
                youColor = primary,
                opponentFraction = if (showOpponentTrack) loopFraction(opponentScore) else null,
                opponentColor = opponentColor,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ScoreReadout(
    score: Int,
    opponentScore: Int,
    primary: Color,
    opponentColor: Color,
    opponentPending: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = tightTextStyle(46.sp, FontWeight.Black)
        Text("$score", color = primary.copy(alpha = 0.85f), style = style, maxLines = 1)
        Text("/", color = Color.White.copy(alpha = 0.35f), style = style, maxLines = 1)
        Text("$opponentScore", color = opponentColor.copy(alpha = 0.85f), style = style, maxLines = 1)
        if (opponentPending > 0) {
            // What the other player is in the middle of adding, before their score updates.
            Text(
                "+$opponentPending",
                color = Color.White,
                style = tightTextStyle(24.sp, FontWeight.Black),
                modifier = Modifier
                    .background(opponentColor, CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PlusButton(
    displayValue: Int,
    clickLabel: String,
    highlighted: Boolean,
    primary: Color,
    deep: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // A slow pulse while a value is staged, so a pending claim is hard to walk away from.
    val transition = rememberInfiniteTransition(label = "plusGlow")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500), RepeatMode.Reverse),
        label = "pulse",
    )
    val scale by animateFloatAsState(if (highlighted) 1.05f else 1f, tween(220), label = "plusScale")
    val glow = if (highlighted) 0.35f + 0.55f * pulse else 0f

    Box(
        Modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.4f)
            .defaultMinSize(minWidth = 56.dp, minHeight = 44.dp)
            .background(primary.copy(alpha = if (highlighted) 0.42f else 0.16f), CircleShape)
            .border(
                width = if (highlighted) 1.6.dp else 1.dp,
                color = primary.copy(alpha = if (highlighted) 0.95f else 0.55f),
                shape = CircleShape,
            )
            .clickable(
                enabled = enabled,
                onClickLabel = clickLabel,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+$displayValue",
            // Compose has no gradient text fill as cheaply as SwiftUI's; the unhighlighted state
            // uses the player's primary, which is what the gradient reads as at this size anyway.
            color = if (highlighted) Color.White else primary.copy(alpha = 0.9f + glow * 0f),
            style = tightTextStyle(22.sp, FontWeight.Black),
            maxLines = 1,
        )
    }
}

@Composable
private fun UndoButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            // 38dp reads right beside the slider, but it is under the 48dp minimum a thumb (or an
            // accessibility service) expects — this keeps the drawing small and the target legal.
            .minimumInteractiveComponentSize()
            .size(38.dp)
            .alpha(if (enabled) 1f else 0.30f)
            .background(Color.White.copy(alpha = 0.08f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(18.dp),
        )
    }
}
