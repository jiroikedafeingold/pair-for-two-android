package com.jirofeingold.pairfortwo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.jirofeingold.pairfortwo.ui.CardGallery
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PairForTwoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Scaffolding while the table is built (PLAN.md §10 phase 6). The menu and the
                    // game table replace this; until then it renders every card state so the
                    // drawing can be checked on a device rather than only compiled.
                    CardGallery(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
