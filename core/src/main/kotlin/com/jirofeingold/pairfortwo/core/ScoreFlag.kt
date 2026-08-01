package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single detected scoring opportunity. Port of the iOS `ScoreFlag`.
 *
 * These drive the coach's flag chips and can pre-fill the manual slider; whether they are
 * auto-applied depends on [ScoringMode].
 */
@Serializable
data class ScoreFlag(
    val kind: Kind,
    val points: Int,
    /**
     * Human-readable detail, e.g. "Fifteen 2", "Run of 3", "Pair", "His Nobs".
     * Kept English here, exactly as iOS does; the view layer localizes for display.
     */
    val detail: String,
) {
    /** The category of the score. Used for grouping, icons, and automatic scoring. */
    @Serializable
    enum class Kind {
        @SerialName("fifteen") FIFTEEN,

        /** Pairs (2), pairs royal / trips (6), double pair royal / quads (12). */
        @SerialName("pair") PAIR,

        @SerialName("run") RUN,
        @SerialName("flush") FLUSH,

        /** Jack in hand matching the starter's suit. */
        @SerialName("nobs") NOBS,

        /** Starter is a Jack — 2 for the dealer, scored at the cut. */
        @SerialName("hisHeels") HIS_HEELS,

        @SerialName("thirtyOne") THIRTY_ONE,
        @SerialName("go") GO,
        @SerialName("lastCard") LAST_CARD,
    }

    /** Stable identity, matching iOS's `ScoreFlag.id`. */
    val id: String get() = "${kind.wireName}|$detail|$points"
}

/** The wire/serial name of a flag kind, matching iOS's raw values. */
val ScoreFlag.Kind.wireName: String
    get() = when (this) {
        ScoreFlag.Kind.FIFTEEN -> "fifteen"
        ScoreFlag.Kind.PAIR -> "pair"
        ScoreFlag.Kind.RUN -> "run"
        ScoreFlag.Kind.FLUSH -> "flush"
        ScoreFlag.Kind.NOBS -> "nobs"
        ScoreFlag.Kind.HIS_HEELS -> "hisHeels"
        ScoreFlag.Kind.THIRTY_ONE -> "thirtyOne"
        ScoreFlag.Kind.GO -> "go"
        ScoreFlag.Kind.LAST_CARD -> "lastCard"
    }

/** Total points represented by this collection of flags. */
val List<ScoreFlag>.totalPoints: Int get() = sumOf { it.points }
