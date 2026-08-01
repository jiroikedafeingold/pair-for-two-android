package com.jirofeingold.pairfortwo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PairForTwoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Placeholder until the menu lands in phase 6 (PLAN.md §10).
                    Placeholder(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun Placeholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Pair for two")
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderPreview() {
    PairForTwoTheme { Placeholder() }
}
