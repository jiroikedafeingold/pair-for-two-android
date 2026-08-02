package com.jirofeingold.pairfortwo.ui

import androidx.annotation.DrawableRes
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
