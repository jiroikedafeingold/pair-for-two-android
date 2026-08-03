package com.jirofeingold.pairfortwo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.jirofeingold.pairfortwo.settings.AppSettings
import com.jirofeingold.pairfortwo.settings.SettingsStore
import com.jirofeingold.pairfortwo.ui.RootScaffold
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

                // Defaults until the first read lands, which is one frame away — not a "loading"
                // state worth a spinner, and the values match what a fresh install stores anyway.
                val settings by store.settings.collectAsStateWithLifecycle(AppSettings())

                RootScaffold(
                    settings = settings,
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
