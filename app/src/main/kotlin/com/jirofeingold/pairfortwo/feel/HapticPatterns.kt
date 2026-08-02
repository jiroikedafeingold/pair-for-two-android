package com.jirofeingold.pairfortwo.feel

import com.jirofeingold.pairfortwo.core.SkunkLevel
import kotlin.math.max
import kotlin.math.min

/**
 * Every haptic pattern in the game, ported one for one from the iOS `GameFeedback.haptic(for:)`,
 * `WinHaptics`, `LoseHaptics` and `DragTickHaptics`.
 *
 * Kept as data, separate from the vibrator, so these read as the same designs the iOS app has —
 * the comments below are the iOS comments, because they describe the intent rather than the API.
 */
object HapticPatterns {

    /** The eleven in-game moments, matching iOS's `GameFeedback.Action`. */
    enum class Action {
        CARD_PLAY,
        DISCARD_SELECT,
        DISCARD_CONFIRM,
        CUT_TAP,
        DECK_LIFT,
        STARTER_REVEAL,
        DEAL,
        GO,
        THIRTY_ONE,
        SCORE,
        ADVANCE,
    }

    fun forAction(action: Action): HapticPattern = when (action) {
        // A crisp "snap" as the card hits the table.
        Action.CARD_PLAY -> HapticPattern(
            listOf(transient(0.0, 0.9f, 0.85f), continuous(0.0, 0.045, 0.5f, 0.6f)),
        )

        Action.DISCARD_SELECT -> HapticPattern(listOf(transient(0.0, 0.55f, 0.55f)))

        Action.DISCARD_CONFIRM -> HapticPattern(
            listOf(transient(0.0, 0.7f, 0.6f), transient(0.07, 0.85f, 0.75f)),
        )

        // A slide then a click — cutting the deck.
        Action.CUT_TAP -> HapticPattern(
            listOf(continuous(0.0, 0.12, 0.5f, 0.3f), transient(0.13, 0.9f, 0.9f)),
        )

        // A rising drag as the top portion is lifted aside.
        Action.DECK_LIFT -> HapticPattern(
            events = listOf(continuous(0.0, 0.26, 0.7f, 0.35f)),
            intensityCurve = listOf(
                CurvePoint(0.0, 0.2f),
                CurvePoint(0.18, 0.7f),
                CurvePoint(0.26, 0.0f),
            ),
        )

        // Turn + a satisfying thud as the starter lands face up.
        Action.STARTER_REVEAL -> HapticPattern(
            listOf(
                transient(0.0, 0.7f, 0.9f),
                continuous(0.02, 0.06, 0.6f, 0.5f),
                transient(0.11, 1.0f, 0.7f),
            ),
        )

        // A rolling riffle: several quick transients tapering off.
        Action.DEAL -> {
            val beats = listOf(0.0, 0.05, 0.095, 0.135, 0.17, 0.205, 0.245, 0.29, 0.35, 0.42)
            HapticPattern(
                beats.mapIndexed { i, t ->
                    val fade = (1.0 - i.toDouble() / beats.size * 0.5).toFloat()
                    transient(t, 0.5f * fade, 0.75f)
                },
            )
        }

        // Two firm taps — "you're on, take the point".
        Action.GO -> HapticPattern(
            listOf(transient(0.0, 0.9f, 0.5f), transient(0.14, 0.9f, 0.5f)),
        )

        // A strong escalating triple with a little rumble — the biggest pegging moment.
        Action.THIRTY_ONE -> HapticPattern(
            listOf(
                transient(0.0, 0.8f, 0.6f),
                transient(0.1, 0.9f, 0.7f),
                transient(0.2, 1.0f, 0.9f),
                continuous(0.2, 0.18, 0.9f, 0.5f),
            ),
        )

        Action.SCORE -> HapticPattern(
            listOf(transient(0.0, 0.9f, 0.7f), continuous(0.01, 0.08, 0.7f, 0.4f)),
        )

        Action.ADVANCE -> HapticPattern(listOf(transient(0.0, 0.7f, 0.6f)))
    }

    /**
     * The win celebration, scaled by skunk level.
     *
     * A long, relentless celebration: two overlapping continuous rumbles (deep + sharp buzz), a
     * dense accelerating fusillade of full-strength taps with crackle, and a big finale barrage.
     * Bigger skunk → longer and crazier.
     *
     * PLAN.md §5.2 anticipated having to chain segments because Android caps composition length.
     * It turns out not to be needed: the two continuous layers and the intensity curve push this
     * down the waveform path, and `createWaveform` has no such limit — seven seconds at the
     * renderer's 10 ms step is 700 entries, which is unremarkable.
     */
    fun win(skunk: SkunkLevel): HapticPattern {
        val duration: Double
        val burstCount: Int
        when (skunk) {
            SkunkLevel.NONE -> { duration = 4.0; burstCount = 46 }
            SkunkLevel.SINGLE -> { duration = 5.5; burstCount = 72 }
            SkunkLevel.DOUBLE -> { duration = 7.0; burstCount = 100 }
        }

        val events = mutableListOf<HapticEvent>()

        // Two continuous layers across the whole celebration: a deep body rumble + a sharp buzz.
        events += continuous(0.0, duration, 1.0f, 0.25f)
        events += continuous(0.0, duration, 0.8f, 0.85f)

        // Dense accelerating fusillade of near-max taps; every few adds a crackle double-tap.
        var t = 0.0
        for (i in 0 until burstCount) {
            val frac = i.toDouble() / burstCount
            t += max(0.035, 0.13 - 0.09 * frac)
            if (t > duration - 0.4) break
            events += transient(
                t,
                min(1.0, 0.85 + 0.2 * frac).toFloat(),
                (if (i % 3 == 0) 0.95 else 0.45 + 0.45 * frac).toFloat(),
            )
            if (i % 5 == 0) events += transient(t + 0.02, 1.0f, 1.0f)   // crackle
        }

        // Finale: a barrage of huge booms + a final swell.
        val finale = max(0.0, duration - 0.5)
        for (dt in listOf(0.0, 0.07, 0.14, 0.22, 0.31, 0.42)) {
            events += transient(finale + dt, 1.0f, if (dt >= 0.31) 0.95f else 0.55f)
        }
        events += continuous(finale, 0.55, 1.0f, 0.6f)

        // Intensity curve: swell in fast and stay high, peaking at the finale.
        return HapticPattern(
            events = events,
            intensityCurve = listOf(
                CurvePoint(0.0, 0.7f),
                CurvePoint(duration * 0.2, 1.0f),
                CurvePoint(duration * 0.6, 0.9f),
                CurvePoint(duration * 0.85, 1.0f),
                CurvePoint(duration, 0.0f),
            ),
        )
    }

    /**
     * A gentle, melancholy "wah-wah" for the losing player — two soft descending thuds under a
     * fading low rumble. Deliberately subdued (the opposite of the win rumble).
     */
    fun lose(): HapticPattern = HapticPattern(
        events = listOf(
            continuous(0.0, 0.9, 0.5f, 0.15f),
            transient(0.0, 0.7f, 0.2f),
            transient(0.35, 0.45f, 0.12f),
        ),
        intensityCurve = listOf(CurvePoint(0.0, 1.0f), CurvePoint(0.9, 0.0f)),
    )

    /**
     * Per-step feedback for the points slider, scaling in strength as the number climbs and
     * stacking a deep transient at the top.
     *
     * @param progress 0…1 position of the value along the track.
     */
    fun sliderTick(progress: Double): HapticPattern {
        val p = progress.coerceIn(0.0, 1.0)
        val events = mutableListOf<HapticEvent>(
            transient(0.0, (0.85 + 0.15 * p).toFloat(), (0.4 + 0.6 * p).toFloat()),
        )
        if (p >= 0.85) {
            events += transient(0.0, 1.0f, 0.05f)
            events += continuous(0.0, 0.09, 1.0f, 0.5f)
            events += transient(0.015, 1.0f, 1.0f)
        }
        return HapticPattern(events)
    }
}
