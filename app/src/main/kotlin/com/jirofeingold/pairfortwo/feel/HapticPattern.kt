package com.jirofeingold.pairfortwo.feel

/**
 * A Core Haptics pattern, expressed in Core Haptics' own terms so the iOS patterns can be ported
 * line for line and read side by side with `GameFeedback.swift` and `Haptics.swift`.
 *
 * Translating to Android's vibrator happens in [HapticsController], deliberately kept separate:
 * the *design* of each effect and the *compromises* needed to render it are different problems, and
 * mixing them would make it impossible to tell which of the two a given oddity came from.
 *
 * ## The compromise, stated plainly
 *
 * Core Haptics has an **intensity** axis and a **sharpness** axis, plus continuous parameter curves.
 * Android has amplitude only. Sharpness is therefore not reproducible — it is used to *choose* which
 * haptic primitive to fire (sharp → a click, dull → a thud), which reads as the same gesture without
 * being the same signal. The target is "feels like the same action", not "is identical".
 */
sealed interface HapticEvent {
    val time: Double

    /** A tap. [intensity] and [sharpness] are both 0…1, as in Core Haptics. */
    data class Transient(
        override val time: Double,
        val intensity: Float,
        val sharpness: Float,
    ) : HapticEvent

    /** A sustained buzz. */
    data class Continuous(
        override val time: Double,
        val duration: Double,
        val intensity: Float,
        val sharpness: Float,
    ) : HapticEvent
}

/** One point on a `CHHapticParameterCurve`. */
data class CurvePoint(val time: Double, val value: Float)

/**
 * A complete pattern: events, plus an optional intensity curve applied across the whole thing.
 */
data class HapticPattern(
    val events: List<HapticEvent>,
    val intensityCurve: List<CurvePoint> = emptyList(),
) {
    /** Wall-clock length, so the renderer knows how long a timeline to build. */
    val duration: Double
        get() = maxOf(
            events.maxOfOrNull { e ->
                when (e) {
                    is HapticEvent.Transient -> e.time + TRANSIENT_WIDTH
                    is HapticEvent.Continuous -> e.time + e.duration
                }
            } ?: 0.0,
            intensityCurve.maxOfOrNull { it.time } ?: 0.0,
        )

    /** True when every event is a tap, which is the case Android can render most faithfully. */
    val isAllTransients: Boolean get() = events.all { it is HapticEvent.Transient }

    /** Intensity multiplier at [t] from the curve, by linear interpolation. 1.0 when there is none. */
    fun curveValue(t: Double): Float {
        if (intensityCurve.isEmpty()) return 1f
        val sorted = intensityCurve.sortedBy { it.time }
        if (t <= sorted.first().time) return sorted.first().value
        if (t >= sorted.last().time) return sorted.last().value
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (t in a.time..b.time) {
                val span = b.time - a.time
                if (span <= 0.0) return b.value
                val f = ((t - a.time) / span).toFloat()
                return a.value + (b.value - a.value) * f
            }
        }
        return sorted.last().value
    }

    companion object {
        /**
         * How long a transient occupies when rendered onto an amplitude timeline. Core Haptics
         * transients are impulses with no duration; a vibrator motor needs some width to produce
         * anything at all, and ~20 ms is about the shortest that reliably reads as a tap.
         */
        const val TRANSIENT_WIDTH = 0.02
    }
}

/** Convenience mirroring the `transient(_:_:_:)` helper in `GameFeedback.swift`. */
fun transient(time: Double, intensity: Float, sharpness: Float) =
    HapticEvent.Transient(time, intensity, sharpness)

/** Convenience mirroring the `continuous(_:_:_:_:)` helper in `GameFeedback.swift`. */
fun continuous(time: Double, duration: Double, intensity: Float, sharpness: Float) =
    HapticEvent.Continuous(time, duration, intensity, sharpness)
