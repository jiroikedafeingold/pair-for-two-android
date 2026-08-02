package com.jirofeingold.pairfortwo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.jirofeingold.pairfortwo.core.GameViewModel
import com.jirofeingold.pairfortwo.core.PlayerID
import com.jirofeingold.pairfortwo.core.ScoringMode
import com.jirofeingold.pairfortwo.ui.GameTableScreen
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme

/**
 * Drops straight into a pass-and-play game.
 *
 * Scaffolding: the menu, connect screen and settings are still to come (PLAN.md §10 phase 6), and
 * `RootScaffold` will own this decision. Going straight to the table means the table can be played
 * and screenshotted on a device now, which is the only honest way to check a layout.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PairForTwoTheme {
                val vm = remember {
                    GameViewModel.loopback(
                        names = mapOf(PlayerID.ONE to "Ada", PlayerID.TWO to "Bo"),
                        colorIDs = mapOf(PlayerID.ONE to 2, PlayerID.TWO to 7),
                        scope = lifecycleScope,
                        // The manual score panel isn't ported yet, so automatic scoring is the
                        // mode that plays end to end today.
                        scoringMode = ScoringMode.AUTO,
                    )
                }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GameTableScreen(vm, Modifier.padding(innerPadding))
                }
            }
        }
    }
}
