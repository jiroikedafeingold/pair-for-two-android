package com.jirofeingold.pairfortwo.ui

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text measured to its glyphs, with no extra leading above or below.
 *
 * Compose pads every `Text` with the font's ascent and descent and lays it out on a default line
 * height. SwiftUI does not, so any layout ported by copying the Swift's point sizes ends up taller
 * on Android than the geometry budget allows. That has already cost two visible bugs: the card's
 * corner index collided with its centre pip, and the scoreboard's digits were sliced off where the
 * flag chips pushed the top band past its fixed height.
 *
 * Use this wherever a ported size is load-bearing for layout. Where text is free to take whatever
 * room it needs, the platform default is fine and arguably more legible.
 */
internal val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

/** A [TextStyle] whose line box is exactly its glyphs — see [TightLineHeight]. */
internal fun tightTextStyle(
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = 0.sp,
): TextStyle = TextStyle(
    fontSize = fontSize,
    fontWeight = fontWeight,
    letterSpacing = letterSpacing,
    lineHeight = fontSize,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = TightLineHeight,
)
