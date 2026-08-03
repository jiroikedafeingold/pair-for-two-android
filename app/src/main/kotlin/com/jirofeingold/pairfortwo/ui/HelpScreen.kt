package com.jirofeingold.pairfortwo.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jirofeingold.pairfortwo.core.Card
import com.jirofeingold.pairfortwo.core.Rank
import com.jirofeingold.pairfortwo.core.Suit
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.FeltMid
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme
import com.jirofeingold.pairfortwo.ui.theme.playerThemes

/**
 * The full "how to play" reference — port of the iOS `HelpView`, reachable from the ? on the menu
 * and on the table.
 *
 * Like iOS, it illustrates itself with the app's **real** cards and scoring control rather than
 * screenshots: the slider below actually works, and a picture of a control can go stale while the
 * control itself cannot. Native chrome (a `TopAppBar` and a scrolling column, not an iOS `Form`),
 * felt strips around the illustrations so they read as bits of the table.
 *
 * **The wording is not iOS's verbatim, because the app isn't.** There is no Play online section
 * (no Game Center equivalent — PLAN.md §0), Bluetooth is not a transport here so connecting is
 * described as same-Wi-Fi, and check-my-count and the scoring replay are left out until those
 * screens are ported. Documenting a button that isn't there would be worse than saying nothing.
 *
 * @param onReplayOnboarding when given (from the menu), offers to replay the welcome tour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onReplayOnboarding: (() -> Unit)? = null,
) {
    // A live throwaway score, so the demo panel below behaves exactly like the real one.
    var demoScore by remember { mutableIntStateOf(0) }
    val addDemo: (Int) -> Unit = { points ->
        demoScore = (demoScore + points).let { if (it >= 121) 0 else it }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("How to play") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(titleContentColor = CribGold),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            FeltStrip {
                CardFan(
                    listOf(
                        Card(Rank.FIVE, Suit.HEARTS),
                        Card(Rank.SIX, Suit.SPADES),
                        Card(Rank.SEVEN, Suit.DIAMONDS),
                        Card(Rank.EIGHT, Suit.CLUBS),
                        Card(Rank.JACK, Suit.HEARTS),
                    ),
                )
            }
            Body(
                "Cribbage for two, one device each. First to **121** wins.",
                align = TextAlign.Center,
            )

            Section("Playing nearby")
            Body("Both devices on the same Wi-Fi — a personal hotspot counts:")
            Bullet("On the menu, tap **Play nearby**.")
            Bullet("One device taps **Host a game**, the other taps **Join a game** and picks the host.")
            Bullet(
                "Some public and guest networks stop devices talking to each other. If you can see " +
                    "each other's names but never connect, that's why — use a personal hotspot.",
            )
            Bullet("An iPhone running Pair for Two shows up in the same list.")

            Section("A hand, step by step")
            FeltStrip {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("The Cut", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                        CardView(Card(Rank.FIVE, Suit.CLUBS), width = 40.dp)
                    }
                    CardFan(
                        listOf(
                            Card(Rank.FOUR, Suit.DIAMONDS),
                            Card(Rank.FIVE, Suit.HEARTS),
                            Card(Rank.SIX, Suit.SPADES),
                            Card(Rank.JACK, Suit.HEARTS),
                        ),
                        width = 40.dp,
                    )
                }
            }
            Bullet("**Cut for deal:** each player taps to cut — low card deals and takes the crib.")
            Bullet("**Discard:** each player sends 2 cards to the dealer's crib.")
            Bullet("**Cut the starter:** the non-dealer taps the deck; the dealer turns up the starter card.")
            Bullet(
                "**The play (pegging):** take turns laying cards and calling the count. Say **Go** " +
                    "when you can't play without passing 31 — you're nudged when a Go or 31 is " +
                    "yours to take.",
            )
            Bullet("**The show:** count in order — non-dealer's hand, dealer's hand, then the crib.")

            Section("Scoring your points")
            Body(
                "**Try it below** — drag the slider and release, then tap the **+N** button to " +
                    "confirm and add the points. (This demo resets at 121.)",
            )
            FeltStrip {
                ScorePanel(
                    name = "You",
                    score = demoScore,
                    opponentScore = 0,
                    primary = playerThemes[1].primary,
                    deep = playerThemes[1].deep,
                    disabled = false,
                    canUndo = false,
                    requireConfirm = true,
                    onAdd = addDemo,
                    onPlusOne = { addDemo(1) },
                    onUndo = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                )
            }
            Bullet("**Slider:** drag to the number of points and let go.")
            Bullet("**+1 button:** tap it repeatedly to count up one at a time.")
            Bullet("**Confirm after release** (Settings): holds the amount until you tap **+N** to confirm.")
            Body("Scoring mode (Settings) applies to the whole game:")
            Bullet("**Automatic** — the app counts and adds every point.")
            Bullet("**Feedback** — the app shows each score; you add it yourself.")
            Bullet("**Player responsibility** — no hints; count it all yourself.")

            Section("Settings")
            Bullet("**Name & colour**, and your **card back**.")
            Bullet("**Scoring mode** — either player can change it, and it applies to both.")
            Bullet("**Feel & effects:** haptics, sound effects, celebration effects, score rings.")

            Section("Tips")
            Bullet("Tap the **?** on the table any time to reopen this guide.")
            Bullet("Step away mid-game and come back — **Rejoin game** from the menu picks it up.")
            Bullet("Back, or the **✕** on the table, leaves the game — it ends for both players.")

            if (onReplayOnboarding != null) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onReplayOnboarding,
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = CribGold,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Replay the welcome tour",
                        color = CribGold,
                        style = tightTextStyle(15.sp, FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

// ---- Building blocks ----

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp), color = Color.White.copy(alpha = 0.08f))
    Text(
        title,
        color = CribGold,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun Body(markdown: String, align: TextAlign = TextAlign.Start) {
    Text(
        emphasised(markdown),
        color = Color.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = align,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A bullet whose text hangs, rather than wrapping back under the dot. */
@Composable
private fun Bullet(markdown: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("•", color = CribGold, style = MaterialTheme.typography.bodyMedium)
        Text(
            emphasised(markdown),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Puts an illustration on felt, so the cards and controls look like they do on the table. */
@Composable
private fun FeltStrip(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(
                Brush.verticalGradient(listOf(FeltMid, FeltDark)),
                RoundedCornerShape(14.dp),
            )
            .padding(vertical = 12.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.widthIn(max = 520.dp), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun CardFan(cards: List<Card>, width: androidx.compose.ui.unit.Dp = 44.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(-width * 0.42f)) {
        for (card in cards) CardView(card, width = width)
    }
}

/**
 * `**bold**` → an [AnnotatedString] with those runs emboldened.
 *
 * SwiftUI's `Text(.init(markdown))` gives iOS this for free; Compose has no equivalent, and pulling
 * in a Markdown library to bold a dozen phrases would be absurd. Anything that isn't a matched pair
 * of `**` is passed through as literal text, so a stray asterisk renders rather than eating the rest
 * of the sentence.
 */
private fun emphasised(markdown: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < markdown.length) {
        val open = markdown.indexOf(MARK, index)
        if (open < 0) {
            append(markdown.substring(index))
            return@buildAnnotatedString
        }
        val close = markdown.indexOf(MARK, open + MARK.length)
        if (close < 0) {
            append(markdown.substring(index))
            return@buildAnnotatedString
        }
        append(markdown.substring(index, open))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(markdown.substring(open + MARK.length, close))
        }
        index = close + MARK.length
    }
}

private const val MARK = "**"

@Preview(showBackground = true, widthDp = 900, heightDp = 420)
@Composable
private fun HelpScreenPreview() {
    PairForTwoTheme {
        HelpScreen(onBack = {}, onReplayOnboarding = {})
    }
}
