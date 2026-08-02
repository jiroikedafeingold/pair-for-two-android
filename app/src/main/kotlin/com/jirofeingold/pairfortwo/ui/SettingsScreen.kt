package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jirofeingold.pairfortwo.core.ScoringMode
import com.jirofeingold.pairfortwo.settings.AppSettings
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme

/**
 * The settings screen — the Android counterpart of the iOS `SettingsView`.
 *
 * **Deliberately native rather than a ported `Form`** (PLAN.md §6): Material 3 `ListItem` rows, a
 * top app bar with a back arrow, and section footers doing the explaining. The felt palette carries
 * the brand; the layout is Android's.
 *
 * Two departures worth naming:
 * - A small [TopAppBar], not the `LargeTopAppBar` the plan sketched. The game is landscape-locked,
 *   and a large bar spends a third of the height on its own title.
 * - No "You" (name, colour) section and no scoring-replay toggle yet — those settings have nothing
 *   to act on until ConnectScreen and ScoringReplay are ported. Every switch here changes something
 *   you can see immediately, which is the point of shipping the screen now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(titleContentColor = CribGold),
            )
        },
    ) { innerPadding ->
        // A fixed, short list — a Column that scrolls, not a LazyColumn. There is nothing here to
        // recycle, and in landscape the whole screen is barely two viewports tall.
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Scoring")
            for (mode in ScoringMode.entries) {
                ListItem(
                    headlineContent = { Text(mode.title) },
                    supportingContent = { Text(mode.detail) },
                    leadingContent = {
                        RadioButton(
                            selected = settings.scoringMode == mode,
                            // The row handles the click, so the button itself is decorative —
                            // null keeps it from being a second stop for a screen reader.
                            onClick = null,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onChange(settings.copy(scoringMode = mode)) },
                )
            }
            SectionFooter("Applies to the whole game — either player can change it.")

            // Nothing to confirm when the app is doing the adding, so iOS hides this section in
            // automatic mode and so do we.
            if (settings.scoringMode != ScoringMode.AUTO) {
                SectionHeader("Scoring slider")
                SwitchRow(
                    title = "Confirm after release",
                    checked = settings.confirmRelease,
                    onCheckedChange = { onChange(settings.copy(confirmRelease = it)) },
                )
                SectionFooter(
                    "Holds the slider value until you tap the +N button, instead of adding it " +
                        "the moment you let go. Applies to both players' panels.",
                )
            }

            SectionHeader("Card back")
            CardBackRow(
                selected = settings.cardBackID,
                onSelect = { onChange(settings.copy(cardBackID = it)) },
            )
            SectionFooter("How the backs of the cards look on your device.")

            SectionHeader("Feel & effects")
            SwitchRow(
                title = "Haptics",
                checked = settings.hapticsEnabled,
                onCheckedChange = { onChange(settings.copy(hapticsEnabled = it)) },
            )
            SwitchRow(
                title = "Sound effects",
                checked = settings.soundEnabled,
                onCheckedChange = { onChange(settings.copy(soundEnabled = it)) },
            )
            SwitchRow(
                title = "Celebration effects",
                checked = settings.celebrationEffects,
                onCheckedChange = { onChange(settings.copy(celebrationEffects = it)) },
            )
            SwitchRow(
                title = "Score progress rings",
                checked = settings.scoreTrackEnabled,
                onCheckedChange = { onChange(settings.copy(scoreTrackEnabled = it)) },
            )
            SectionFooter(
                "Haptics are the vibrations during play and on a win. Sound effects are the " +
                    "in-game sounds. Celebration effects are the fireworks and flash on the win " +
                    "screen (the win screen itself still shows). Score progress rings trace " +
                    "each player's colour around the scores, closing the loop at 121.",
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = 0.08f))
    Text(
        text,
        color = CribGold,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // One tap target for the whole row, with the switch along for the ride — the Material
        // pattern, and far easier to hit than a 52dp switch at the far edge of a landscape screen.
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

/** The three backs, shown at the size they are dealt at, with the selected one ringed. */
@Composable
private fun CardBackRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (back in CardBack.entries) {
            val isSelected = back.id == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { onSelect(back.id) },
            ) {
                CardView(
                    null,
                    faceUp = false,
                    width = 58.dp,
                    cardBackID = back.id,
                    modifier = Modifier
                        .background(
                            if (isSelected) CribGold.copy(alpha = 0.18f) else Color.Transparent,
                            RoundedCornerShape(11.dp),
                        )
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) CribGold else Color.Transparent,
                            shape = RoundedCornerShape(11.dp),
                        )
                        .padding(2.dp),
                )
                Text(
                    back.displayName,
                    color = if (isSelected) CribGold else Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 420)
@Composable
private fun SettingsScreenPreview() {
    PairForTwoTheme {
        SettingsScreen(settings = AppSettings(), onChange = {}, onBack = {})
    }
}
