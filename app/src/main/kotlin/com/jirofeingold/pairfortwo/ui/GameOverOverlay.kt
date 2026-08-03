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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jirofeingold.pairfortwo.core.SkunkLevel
import com.jirofeingold.pairfortwo.ui.theme.CribGold

/**
 * The game-over screens — ports of the iOS `WinnerOverlay` and `LoserOverlay`.
 *
 * Two different screens on purpose: the winner gets fireworks, confetti and a rising celebration
 * scaled by how badly they won; the loser gets a subdued, rainy card. In pass-and-play there is only
 * one device, so it shows the winner's.
 */

// ---- Skunk presentation ----
//
// `SkunkLevel` itself lives in :core because it is derived from the score. Its wording and colours
// are presentation, so they live here.

val SkunkLevel.title: String
    get() = when (this) {
        SkunkLevel.NONE -> "VICTORY"
        SkunkLevel.SINGLE -> "SKUNKED!"
        SkunkLevel.DOUBLE -> "DOUBLE SKUNK!"
    }

val SkunkLevel.subtitle: String
    get() = when (this) {
        SkunkLevel.NONE -> "Well played"
        SkunkLevel.SINGLE -> "A clean sweep"
        SkunkLevel.DOUBLE -> "An absolute thrashing"
    }

val SkunkLevel.loserTitle: String
    get() = when (this) {
        SkunkLevel.NONE -> "YOU LOST"
        SkunkLevel.SINGLE -> "SKUNKED"
        SkunkLevel.DOUBLE -> "DOUBLE SKUNKED"
    }

val SkunkLevel.loserSubtitle: String
    get() = when (this) {
        SkunkLevel.NONE -> "Good game — rematch?"
        SkunkLevel.SINGLE -> "Ouch. Run it back?"
        SkunkLevel.DOUBLE -> "Brutal. Get 'em next time."
    }

val SkunkLevel.accentColors: List<Color>
    get() = when (this) {
        SkunkLevel.NONE -> listOf(CribGold, Color(0xFFFFD973))
        SkunkLevel.SINGLE -> listOf(Color(0xFFFFBF40), Color(0xFFFF7326))
        SkunkLevel.DOUBLE -> listOf(Color(0xFFFF5973), Color(0xFFD933A6), Color(0xFF734DF2))
    }

private const val SKUNK_CHAR = "🦨"

// ---- Winner ----

@Composable
fun WinnerOverlay(
    winnerName: String,
    skunk: SkunkLevel,
    winnerColor: Color,
    onPlayAgain: () -> Unit,
    /** Leave the game. Null while there is no menu to leave to — see `GameTableScreen`. */
    onExit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    celebrationEffects: Boolean = true,
    /** The other player has disconnected, so a rematch isn't possible — see [GameOverButtons]. */
    opponentLeft: Boolean = false,
) {
    var animateIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animateIn = true }
    val appear by animateFloatAsState(
        if (animateIn) 1f else 0f,
        tween(600),
        label = "winAppear",
    )

    val effectColors = if (skunk == SkunkLevel.NONE) {
        listOf(winnerColor, CribGold, Color.White)
    } else {
        skunk.accentColors
    }

    val transition = rememberInfiniteTransition(label = "win")
    val pulse by transition.animateFloat(
        0.95f, 1.08f,
        infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "pulse",
    )
    val tilt by transition.animateFloat(
        -6f, 6f,
        infiniteRepeatable(tween(1_600), RepeatMode.Reverse),
        label = "tilt",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f * appear)),
        contentAlignment = Alignment.Center,
    ) {
        if (celebrationEffects) {
            FireworksView(effectColors, Modifier.fillMaxSize().alpha(appear))
        }
        ConfettiBurst(effectColors, Modifier.fillMaxSize().alpha(appear))

        Column(
            Modifier
                .scale(0.8f + 0.2f * appear)
                .alpha(appear)
                .widthIn(max = 340.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(32.dp))
                .border(
                    1.5.dp,
                    Brush.linearGradient(
                        if (skunk == SkunkLevel.NONE) {
                            listOf(winnerColor.copy(alpha = 0.7f), winnerColor.copy(alpha = 0.2f))
                        } else {
                            skunk.accentColors
                        },
                    ),
                    RoundedCornerShape(32.dp),
                )
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                // The halo behind the icon, breathing.
                Box(
                    Modifier
                        .size(130.dp)
                        .scale(pulse)
                        .background(
                            Brush.radialGradient(
                                listOf(winnerColor.copy(alpha = 0.6f), Color.Transparent),
                            ),
                            CircleShape,
                        ),
                )
                WinnerIcon(skunk, tilt)
            }

            Text(
                "${winnerName.uppercase()} WINS",
                color = Color.White.copy(alpha = 0.75f),
                style = tightTextStyle(13.sp, FontWeight.Black, letterSpacing = 3.2.sp),
            )
            Text(
                skunk.title,
                color = skunk.accentColors.first(),
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = tightTextStyle(if (skunk == SkunkLevel.DOUBLE) 30.sp else 34.sp, FontWeight.Black),
            )
            Text(
                skunk.subtitle,
                color = Color.White.copy(alpha = 0.6f),
                style = tightTextStyle(13.sp, FontWeight.SemiBold),
            )

            GameOverButtons(opponentLeft, onPlayAgain, onExit)
        }
    }
}

@Composable
private fun WinnerIcon(skunk: SkunkLevel, tilt: Float) {
    when (skunk) {
        // iOS uses SF Symbols' crown.fill, which Material has no equivalent of; the trophy is the
        // closest thing that reads as "you won" rather than as a generic star.
        SkunkLevel.NONE -> Icon(
            Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = CribGold,
            modifier = Modifier
                .size(72.dp)
                .rotate(tilt),
        )
        SkunkLevel.SINGLE -> Text(
            SKUNK_CHAR,
            fontSize = 76.sp,
            modifier = Modifier.rotate(tilt * 1.6f),
        )
        SkunkLevel.DOUBLE -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(SKUNK_CHAR, fontSize = 64.sp, modifier = Modifier.rotate(-13f + tilt * 0.8f))
            Text(SKUNK_CHAR, fontSize = 64.sp, modifier = Modifier.rotate(13f - tilt * 0.8f))
        }
    }
}

// ---- Loser ----

@Composable
fun LoserOverlay(
    winnerName: String,
    skunk: SkunkLevel,
    onPlayAgain: () -> Unit,
    /** Leave the game. Null while there is no menu to leave to — see `GameTableScreen`. */
    onExit: (() -> Unit)?,
    modifier: Modifier = Modifier,
    celebrationEffects: Boolean = true,
    /** The other player has disconnected, so a rematch isn't possible — see [GameOverButtons]. */
    opponentLeft: Boolean = false,
) {
    var animateIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animateIn = true }
    val appear by animateFloatAsState(if (animateIn) 1f else 0f, tween(600), label = "loseAppear")

    val slate = Color(0xFF8592AE)
    val transition = rememberInfiniteTransition(label = "lose")
    val droop by transition.animateFloat(
        -3f, 5f,
        infiniteRepeatable(tween(1_800), RepeatMode.Reverse),
        label = "droop",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.55f * appear),
                        Color.Black.copy(alpha = 0.72f * appear),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (celebrationEffects) {
            SadRainView(Modifier.fillMaxSize().alpha(0.9f * appear))
        }

        Column(
            Modifier
                .scale(0.8f + 0.2f * appear)
                .alpha(appear)
                .widthIn(max = 340.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
                .border(1.5.dp, slate.copy(alpha = 0.35f), RoundedCornerShape(32.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(130.dp)
                        .background(
                            Brush.radialGradient(listOf(slate.copy(alpha = 0.5f), Color.Transparent)),
                            CircleShape,
                        ),
                )
                Text(
                    "😔",
                    fontSize = 60.sp,
                    modifier = Modifier.graphicsLayer { translationY = droop },
                )
            }

            Text(
                skunk.loserTitle,
                color = slate,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = tightTextStyle(if (skunk == SkunkLevel.DOUBLE) 30.sp else 34.sp, FontWeight.Black),
            )
            Text(
                "${winnerName.uppercase()} WINS",
                color = Color.White.copy(alpha = 0.6f),
                style = tightTextStyle(13.sp, FontWeight.Black, letterSpacing = 3.2.sp),
            )
            Text(
                skunk.loserSubtitle,
                color = Color.White.copy(alpha = 0.6f),
                style = tightTextStyle(13.sp, FontWeight.SemiBold),
            )

            GameOverButtons(opponentLeft, onPlayAgain, onExit)
        }
    }
}

/**
 * The end-of-game buttons, shared by the winner and loser cards.
 *
 * With the opponent gone there is no rematch to offer, so the big gold button becomes the way home
 * and the quieter "Back to menu" link below it would just be a duplicate — iOS drops it in that case
 * and so do we.
 */
@Composable
private fun GameOverButtons(
    opponentLeft: Boolean,
    onPlayAgain: () -> Unit,
    onExit: (() -> Unit)?,
) {
    if (opponentLeft && onExit != null) {
        PrimaryButton("BACK TO MENU", Icons.Filled.Home, onExit)
        return
    }
    PrimaryButton("PLAY AGAIN", Icons.Filled.Refresh, onPlayAgain)
    if (onExit != null) {
        Text(
            "Back to menu",
            color = Color.White.copy(alpha = 0.6f),
            style = tightTextStyle(12.sp, FontWeight.SemiBold),
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExit,
                )
                .padding(4.dp),
        )
    }
}

/** The big gold capsule button. */
@Composable
private fun PrimaryButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .background(
                Brush.linearGradient(listOf(CribGold, Color(0xFFC78C33))),
                CircleShape,
            )
            .border(1.2.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 26.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.88f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            color = Color.Black.copy(alpha = 0.88f),
            style = tightTextStyle(15.sp, FontWeight.Black, letterSpacing = 2.2.sp),
        )
    }
}
