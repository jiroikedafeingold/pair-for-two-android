package com.jirofeingold.pairfortwo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.jirofeingold.pairfortwo.feel.GameFeedback
import com.jirofeingold.pairfortwo.persistence.AndroidGamePersistence
import com.jirofeingold.pairfortwo.settings.SettingsStore
import com.jirofeingold.pairfortwo.ui.RootScaffold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme
import kotlinx.coroutines.launch

/**
 * The one activity. Everything above it is Compose, and [RootScaffold] owns the menu → connect →
 * game flow (PLAN.md §10 phase 6).
 *
 * The long-lived pieces are built here rather than in composition because they outlive any one
 * screen and are tied to the activity's lifetime: the feedback engine holds a `SoundPool`, the
 * persistence layer holds a writer coroutine, and both are handed the activity's `lifecycleScope` so
 * a game in progress survives a recomposition but not the activity.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super, as the library requires: this swaps the splash theme for the app's own and
        // holds the splash until the first frame is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PairForTwoTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                val feedback = remember { GameFeedback(context, lifecycleScope) }
                DisposableEffect(Unit) { onDispose { feedback.release() } }

                val store = remember { SettingsStore(context.applicationContext) }
                val persistence = remember {
                    AndroidGamePersistence(context.applicationContext, lifecycleScope)
                }

                // Null until the first read lands, a frame or two away, and the felt fills the gap.
                //
                // Composing the app against defaults first would be *almost* harmless — except that
                // `hasOnboarded` defaults to false, so a returning player would be shown a frame of
                // the welcome tour before the real value arrived. Waiting is simpler than teaching
                // every reader of a setting to distinguish "not loaded" from "false".
                val settings by store.settings.collectAsStateWithLifecycle(null)

                val current = settings
                if (current == null) {
                    Box(Modifier.fillMaxSize().background(FeltDark))
                } else {
                    RootScaffold(
                        settings = current,
                        onChangeSettings = { next -> scope.launch { store.update { next } } },
                        feedback = feedback,
                        persistence = persistence,
                        scope = lifecycleScope,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
