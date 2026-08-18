package com.jirofeingold.pairfortwo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jirofeingold.pairfortwo.R
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import kotlinx.coroutines.delay

/**
 * The launch art — the same picture the iOS app shows on its launch screen.
 *
 * **Why this is in the app rather than the system splash.** From Android 12 the platform *owns* the
 * splash: it is an icon on a solid colour, and a full-bleed launch image cannot be given to it at
 * all (`core-splashscreen` backports the same model to older versions). The system splash therefore
 * stays as the launcher icon on felt, and the shared artwork is shown here, in the app's own first
 * frame, so the two platforms open on the same picture.
 *
 * It costs a beat, so it earns it: it also covers the wait for the first settings read, which
 * otherwise showed a blank felt rectangle. The art is up for [MIN_SHOW_MS] *or* until the app is
 * ready, whichever is longer, and a tap skips it.
 */
@Composable
fun SplashArt(ready: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    var minimumElapsed by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(MIN_SHOW_MS)
        minimumElapsed = true
    }

    // Fade once the art has had its moment and there is something to fade *to*.
    val finished = dismissed || (minimumElapsed && ready)
    val alpha by animateFloatAsState(
        targetValue = if (finished) 0f else 1f,
        animationSpec = tween(FADE_MS),
        label = "splashFade",
        finishedListener = { if (it == 0f) onDone() },
    )

    Box(
        modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(FeltDark)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { dismissed = true },
            )
            .semantics { contentDescription = "Pair for Two" },
    ) {
        Image(
            painter = painterResource(R.drawable.splash_art),
            contentDescription = null,
            // The art is 2.18:1 and a landscape phone is about 2.2:1, so cropping trims a hair off
            // the sides rather than letterboxing the felt.
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Long enough to register as the app's own opening image, short enough not to be a wait. */
private const val MIN_SHOW_MS = 1_100L
private const val FADE_MS = 420
