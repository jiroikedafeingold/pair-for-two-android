package com.jirofeingold.pairfortwo.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.compositionLocalOf
import com.jirofeingold.pairfortwo.R

/**
 * The available face-down card-back designs — port of the iOS `CardBack`.
 *
 * The artwork is the same three images, converted from the iOS asset catalog's PNGs to WebP
 * (2.2 MB → 308 KB, visually lossless). Stored as an Int in settings, matching iOS's
 * `@AppStorage("cardBackID")`, so a player's choice means the same thing on both platforms.
 */
enum class CardBack(val id: Int, val displayName: String, @param:DrawableRes val res: Int) {
    ROYAL(0, "Royal", R.drawable.card_back_royal),
    CELESTIAL(1, "Celestial", R.drawable.card_back_celestial),
    MIDNIGHT(2, "Midnight", R.drawable.card_back_midnight);

    companion object {
        /** Resolve a stored id to a back, defaulting to Royal for anything unexpected. */
        fun from(id: Int): CardBack = entries.firstOrNull { it.id == id } ?: ROYAL
    }
}

/**
 * The chosen card back, ambient for the whole table.
 *
 * Every face-down card should use it, and there are a dozen of those across the table, the pile and
 * the hand — threading an id through all of them would be noise at each call site for a value that
 * never varies within a screen. iOS reads the same setting straight from `@AppStorage` inside
 * `CardView`; this is the Compose equivalent, with the setting still owned by one place.
 */
val LocalCardBackID = compositionLocalOf { 0 }
