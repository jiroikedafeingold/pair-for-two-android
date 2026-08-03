package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jirofeingold.pairfortwo.core.ScoringMode
import com.jirofeingold.pairfortwo.settings.AppSettings
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.FeltMid
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme
import com.jirofeingold.pairfortwo.ui.theme.playerTheme
import com.jirofeingold.pairfortwo.ui.theme.playerThemes
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * The first-run welcome — port of the iOS `OnboardingView`: what the app is, how to connect, how
 * scoring works, where Settings live, then a name prompt.
 *
 * `HorizontalPager` with a dot indicator, as PLAN.md §6 calls for. Each page scrolls if its content
 * is taller than the screen but stays centred when it fits, so nothing is ever cut off on a short
 * landscape phone — the same accommodation the Swift makes.
 *
 * **Replayable.** From the menu's Help it runs as a tour and ends on "Done"; on a true first run it
 * ends with the name prompt and picks a random colour, so two players who both just installed the
 * app don't turn up as identically-coloured "Player"s.
 *
 * The Play online slide is gone — Android has no Game Center equivalent (PLAN.md §0) — and Bluetooth
 * isn't a transport here, so connecting is described as same-Wi-Fi.
 *
 * @param isReplay opened from Help rather than on first launch: skip the name prompt and the random
 *   colour, since both are already set.
 */
@Composable
fun OnboardingScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    isReplay: Boolean = false,
) {
    var askName by remember { mutableStateOf(false) }
    val pager = rememberPagerState(pageCount = { SLIDES.size })
    val scope = rememberCoroutineScope()

    // Only a true first run personalises: a random colour, and an empty name to type into rather
    // than the word "Player" to clear first.
    LaunchedEffect(isReplay) {
        if (isReplay) return@LaunchedEffect
        onChange(
            settings.copy(
                localColorID = Random.nextInt(playerThemes.size),
                localName = if (settings.localName == AppSettings().localName) "" else settings.localName,
            ),
        )
    }

    Box(
        modifier.background(Brush.verticalGradient(listOf(FeltMid, FeltDark))),
    ) {
        if (askName) {
            NameEntry(settings = settings, onChange = onChange, onDone = onFinish)
            return@Box
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Skip",
                color = Color.White.copy(alpha = 0.8f),
                style = tightTextStyle(15.sp, FontWeight.SemiBold),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onFinish,
                    ),
            )

            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                Slide(
                    slide = SLIDES[page],
                    scoringMode = settings.scoringMode,
                    onPickScoringMode = { onChange(settings.copy(scoringMode = it)) },
                )
            }

            Row(
                Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(SLIDES.size) { i ->
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (i == pager.currentPage) CribGold else Color.White.copy(alpha = 0.3f),
                                CircleShape,
                            ),
                    )
                }
            }

            val onLast = pager.currentPage == SLIDES.lastIndex
            GoldButton(
                if (onLast && isReplay) "Done" else if (onLast) "Continue" else "Continue",
            ) {
                if (!onLast) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                } else if (isReplay) {
                    onFinish()
                } else {
                    askName = true
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ---- Slides ----

private class SlideSpec(
    val icon: ImageVector,
    val title: String,
    val body: String,
    /** The scoring page swaps its body paragraph for a live picker. */
    val interactiveScoring: Boolean = false,
)

private val SLIDES = listOf(
    SlideSpec(
        icon = Icons.Filled.Style,
        title = "Pair for Two",
        body = "Cribbage on two devices — one for each player. Deal, cut, peg, and count your way to 121.",
    ),
    SlideSpec(
        icon = Icons.Filled.Wifi,
        title = "Two devices, one table",
        body = "Play over your Wi-Fi — no internet, no account. One device taps Host a game, the " +
            "other taps Join a game. A personal hotspot works too, and an iPhone running Pair for " +
            "Two can join you.",
    ),
    SlideSpec(
        icon = Icons.Filled.Tune,
        title = "Keep your own score",
        body = "Add your points at the top: drag the slider and let go, or tap +1 to count up one " +
            "at a time. Turn on \"Confirm after release\" in Settings to review before it counts.",
    ),
    SlideSpec(
        icon = Icons.Filled.CheckCircle,
        title = "How do you want to score?",
        body = "Pick who keeps score — change it anytime in Settings.",
        interactiveScoring = true,
    ),
    SlideSpec(
        icon = Icons.Filled.Settings,
        title = "Make it yours",
        body = "Tap the gear on the table for settings: name & colour, card back, scoring mode, and " +
            "toggles for haptics, sound and effects. Tap the ? any time for the full how-to.",
    ),
)

@Composable
private fun Slide(
    slide: SlideSpec,
    scoringMode: ScoringMode,
    onPickScoringMode: (ScoringMode) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(
            if (slide.interactiveScoring) 10.dp else 16.dp,
            Alignment.CenterVertically,
        ),
    ) {
        // The scoring page drops the big icon and its body paragraph to leave room for three
        // options on a short landscape screen — the title asks the question and each option
        // explains itself.
        if (!slide.interactiveScoring) {
            Icon(
                slide.icon,
                contentDescription = null,
                tint = CribGold,
                modifier = Modifier.size(46.dp),
            )
        }
        Text(
            slide.title,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = tightTextStyle(if (slide.interactiveScoring) 21.sp else 25.sp, FontWeight.Black),
        )
        if (slide.interactiveScoring) {
            ScoringPicker(scoringMode, onPickScoringMode)
        } else {
            Text(
                slide.body,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
    }
}

/** One compact line per mode, so all three fit without scrolling. */
@Composable
private fun ScoringPicker(selected: ScoringMode, onPick: (ScoringMode) -> Unit) {
    Column(
        Modifier.widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ScoringMode.entries) {
            val isSelected = mode == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Color.White.copy(alpha = if (isSelected) 0.14f else 0.06f),
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.dp,
                        color = if (isSelected) CribGold.copy(alpha = 0.7f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(mode) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) CribGold else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
                Text(mode.title, color = Color.White, style = tightTextStyle(15.sp, FontWeight.SemiBold))
                Text(
                    "— ${mode.blurb}",
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    style = tightTextStyle(12.sp),
                )
            }
        }
    }
}

/** A shorter gloss than `ScoringMode.detail`, so an option stays on one line here. */
private val ScoringMode.blurb: String
    get() = when (this) {
        ScoringMode.AUTO -> "the app scores for you"
        ScoringMode.FEEDBACK -> "you score, with hints"
        ScoringMode.OFF -> "you score, no hints"
    }

// ---- Name entry ----

@Composable
private fun NameEntry(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onDone: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            // safeDrawing already includes the IME inset, so with the activity set to adjustResize
            // this shrinks to the strip above the keyboard and the content scrolls inside it. The
            // keyboard's own Go key finishes too, which on a landscape phone is the quicker way.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(playerTheme(settings.localColorID).primary, CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
            )
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(34.dp),
            )
        }

        Text("What's your name?", color = Color.White, style = tightTextStyle(24.sp, FontWeight.Black))

        OutlinedTextField(
            value = settings.localName,
            onValueChange = { onChange(settings.copy(localName = it.take(24))) },
            placeholder = { Text("Your name", color = Color.White.copy(alpha = 0.45f)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onDone() }),
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = CribGold.copy(alpha = 0.7f),
                unfocusedBorderColor = CribGold.copy(alpha = 0.4f),
                focusedContainerColor = Color.White.copy(alpha = 0.12f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.12f),
                cursorColor = CribGold,
            ),
            modifier = Modifier
                .widthIn(max = 360.dp)
                .focusRequester(focus),
        )

        Text(
            "Your colour was picked for you — change either in Settings.",
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
        )

        GoldButton("Start playing", onDone)
    }
}

// ---- Shared ----

@Composable
private fun GoldButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.Black,
        style = tightTextStyle(16.sp, FontWeight.Bold),
        modifier = Modifier
            .background(CribGold, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
    )
}

@Preview(showBackground = true, widthDp = 900, heightDp = 420)
@Composable
private fun OnboardingPreview() {
    PairForTwoTheme {
        OnboardingScreen(
            settings = AppSettings(),
            onChange = {},
            onFinish = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
