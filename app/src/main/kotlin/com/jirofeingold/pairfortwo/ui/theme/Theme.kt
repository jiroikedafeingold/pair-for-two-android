package com.jirofeingold.pairfortwo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app's colour scheme.
 *
 * **Deliberately not Material You.** The felt/gold palette and the twelve player themes are
 * shared identity with the iOS app; dynamic colour would recolour the table per-device and
 * break that parity. There is likewise no light variant — the game is a dark green felt
 * table on both platforms. See PLAN.md §6.
 *
 * Material 3 colours are still used for the *chrome* (settings, connect, dialogs), which is
 * where the app should feel native; they are just derived from the felt palette rather than
 * from the wallpaper.
 */
private val FeltColorScheme = darkColorScheme(
    primary = CribGold,
    onPrimary = CardInk,
    secondary = CribGold,
    onSecondary = CardInk,
    background = FeltDark,
    onBackground = CardFace,
    surface = FeltMid,
    onSurface = CardFace,
    error = CardRed,
    onError = CardFace,
)

@Composable
fun PairForTwoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FeltColorScheme,
        typography = Typography,
        content = content,
    )
}
