package com.jirofeingold.pairfortwo.core

/**
 * How badly the loser lost — port of the iOS `SkunkLevel`.
 *
 * Cribbage skunk lines: the loser under 61 is a double skunk, under 91 a skunk. Lives in `:core`
 * rather than with the UI because it is derived from the score, and both the winner overlay and the
 * win haptic scale off it.
 */
enum class SkunkLevel {
    NONE,
    SINGLE,
    DOUBLE;

    companion object {
        fun of(loserScore: Int): SkunkLevel = when {
            loserScore < 61 -> DOUBLE
            loserScore < 91 -> SINGLE
            else -> NONE
        }
    }
}
