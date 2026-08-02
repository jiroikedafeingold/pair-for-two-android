package com.jirofeingold.pairfortwo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jirofeingold.pairfortwo.core.GameViewModel
import com.jirofeingold.pairfortwo.core.PlayerID
import com.jirofeingold.pairfortwo.settings.AppSettings
import com.jirofeingold.pairfortwo.settings.SettingsStore
import com.jirofeingold.pairfortwo.feel.GameFeedback
import com.jirofeingold.pairfortwo.ui.GameTableScreen
import com.jirofeingold.pairfortwo.ui.LocalCardBackID
import com.jirofeingold.pairfortwo.ui.SettingsScreen
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme
import kotlinx.coroutines.launch

/**
 * Drops straight into a pass-and-play game, with Settings reachable from the table.
 *
 * Scaffolding: the menu and connect screen are still to come (PLAN.md §10 phase 6), and
 * `RootScaffold` will own this decision. Going straight to the table means the table can be played
 * and screenshotted on a device now, which is the only honest way to check a layout.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PairForTwoTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val feedback = remember { GameFeedback(context, lifecycleScope) }
                DisposableEffect(Unit) { onDispose { feedback.release() } }

                val store = remember { SettingsStore(context.applicationContext) }
                // Defaults until the first read lands, which is one frame away — not a "loading"
                // state worth a spinner, and the values match what a fresh install stores anyway.
                val settings by store.settings.collectAsStateWithLifecycle(AppSettings())

                val vm = remember {
                    GameViewModel.loopback(
                        names = mapOf(PlayerID.ONE to "Ada", PlayerID.TWO to "Bo"),
                        colorIDs = mapOf(PlayerID.ONE to 2, PlayerID.TWO to 7),
                        scope = lifecycleScope,
                        scoringMode = AppSettings().scoringMode,
                    )
                }

                // Sound and haptics read their switch on every play, so pushing the setting in is
                // enough — no need to rebuild anything.
                LaunchedEffect(settings.soundEnabled, settings.hapticsEnabled) {
                    feedback.soundEnabled = settings.soundEnabled
                    feedback.hapticsEnabled = settings.hapticsEnabled
                }
                // The scoring mode belongs to the game, not the device: the view model broadcasts
                // it so a networked opponent switches with you.
                LaunchedEffect(settings.scoringMode) { vm.setScoringMode(settings.scoringMode) }

                var showingSettings by remember { mutableStateOf(false) }
                BackHandler(enabled = showingSettings) { showingSettings = false }

                CompositionLocalProvider(LocalCardBackID provides settings.cardBackID) {
                    // Two full screens rather than one Scaffold with swapped content: Settings owns
                    // its own app bar and insets, and the table has neither.
                    if (showingSettings) {
                        SettingsScreen(
                            settings = settings,
                            onChange = { next -> scope.launch { store.update { next } } },
                            onBack = { showingSettings = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                            GameTableScreen(
                                vm,
                                Modifier.padding(innerPadding),
                                feedback,
                                confirmRelease = settings.confirmRelease,
                                scoreTrackEnabled = settings.scoreTrackEnabled,
                                celebrationEffects = settings.celebrationEffects,
                                onOpenSettings = { showingSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
