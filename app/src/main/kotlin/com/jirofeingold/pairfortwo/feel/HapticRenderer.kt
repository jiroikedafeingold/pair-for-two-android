package com.jirofeingold.pairfortwo.feel

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a [HapticPattern] into something Android's vibrator can play.
 *
 * Pure, with no Android dependencies, so the translation can be unit-tested on the JVM — which
 * matters more here than usual, because the output is a vibration nobody can assert by feel. The
 * shapes it produces (a tap where iOS taps, a swell where iOS swells) are checkable even though the
 * sensation isn't.
 *
 * ## Two rendering strategies
 *
 * - **All-transient patterns → a primitive composition.** Android's composition primitives are
 *   tuned, crisp taps, far better than anything a raw amplitude envelope produces. Sharpness picks
 *   *which* primitive; intensity becomes its scale.
 * - **Anything with a continuous event or an intensity curve → an amplitude waveform.** A
 *   composition can't express a sustained buzz, and mixing the two in one effect isn't possible, so
 *   a pattern that needs a swell is rendered entirely as a waveform.
 */
object HapticRenderer {

    /** Timeline resolution. 10 ms is fine enough for the curves here and keeps arrays small. */
    const val STEP_MS = 10

    /** Android's composition primitives, named so this file needn't import the Android SDK. */
    enum class Primitive { CLICK, TICK, LOW_TICK, THUD, QUICK_RISE }

    /** One entry in a primitive composition: what to fire, how hard, and how long after the last. */
    data class CompositionStep(val primitive: Primitive, val scale: Float, val delayMs: Int)

    /** Amplitudes 0…255 paired with equal-length timings, ready for `createWaveform`. */
    data class Waveform(val timings: LongArray, val amplitudes: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Waveform && timings.contentEquals(other.timings) &&
                amplitudes.contentEquals(other.amplitudes)

        override fun hashCode(): Int = timings.contentHashCode() * 31 + amplitudes.contentHashCode()
    }

    /**
     * Sharpness decides which primitive stands in for a tap.
     *
     * This is where the port is least faithful and most deliberate: Core Haptics sharpness is a
     * continuous timbre control with no Android equivalent, so it is quantised to the primitive
     * whose character is closest. A sharp snap becomes a CLICK; a dull thump becomes a THUD.
     */
    fun primitiveFor(sharpness: Float): Primitive = when {
        sharpness >= 0.8f -> Primitive.CLICK
        sharpness >= 0.5f -> Primitive.TICK
        sharpness >= 0.25f -> Primitive.LOW_TICK
        else -> Primitive.THUD
    }

    /**
     * Renders an all-transient pattern as a composition. Returns null if the pattern contains
     * anything a composition can't express, in which case the caller falls back to [toWaveform].
     */
    fun toComposition(pattern: HapticPattern): List<CompositionStep>? {
        if (!pattern.isAllTransients || pattern.events.isEmpty()) return null
        if (pattern.intensityCurve.isNotEmpty()) return null

        val transients = pattern.events
            .filterIsInstance<HapticEvent.Transient>()
            .sortedBy { it.time }

        var previous = 0.0
        return transients.map { event ->
            val delay = ((event.time - previous) * 1000).roundToInt().coerceAtLeast(0)
            previous = event.time
            CompositionStep(
                primitive = primitiveFor(event.sharpness),
                scale = event.intensity.coerceIn(0f, 1f),
                delayMs = delay,
            )
        }
    }

    /**
     * Renders any pattern as an amplitude envelope.
     *
     * Continuous events fill their span; transients are widened to [HapticPattern.TRANSIENT_WIDTH]
     * because a motor can't produce an impulse of zero duration. Overlapping events sum and clamp,
     * which is what makes the win pattern's taps ride on top of its rumble rather than replace it.
     */
    fun toWaveform(pattern: HapticPattern): Waveform? {
        val duration = pattern.duration
        if (duration <= 0.0) return null
        val steps = ceil(duration * 1000 / STEP_MS).toInt().coerceAtLeast(1)
        val level = FloatArray(steps)

        fun fill(from: Double, to: Double, value: Float) {
            val first = (from * 1000 / STEP_MS).toInt().coerceIn(0, steps - 1)
            val last = ceil(to * 1000 / STEP_MS).toInt().coerceIn(1, steps)
            for (i in first until last) level[i] = min(1f, level[i] + value)
        }

        for (event in pattern.events) {
            when (event) {
                is HapticEvent.Transient ->
                    fill(event.time, event.time + HapticPattern.TRANSIENT_WIDTH, event.intensity)
                is HapticEvent.Continuous ->
                    fill(event.time, event.time + event.duration, event.intensity)
            }
        }

        // The intensity curve scales the whole timeline, exactly as CHHapticParameterCurve does.
        for (i in level.indices) {
            level[i] *= pattern.curveValue((i * STEP_MS) / 1000.0)
        }

        if (level.all { it <= 0f }) return null
        return Waveform(
            timings = LongArray(steps) { STEP_MS.toLong() },
            // Amplitude 0 means "off", so anything audible has to be at least 1 — rounding a quiet
            // step to 0 would punch a silent hole in the middle of a swell.
            amplitudes = IntArray(steps) { i ->
                val a = (level[i] * 255).roundToInt()
                if (level[i] > 0f) a.coerceIn(1, 255) else 0
            },
        )
    }

    /**
     * The last resort: on/off timings for a device with no amplitude control at all.
     *
     * Everything above a half-strength threshold becomes "on". Crude by definition, but the
     * alternative on such a device is either silence or one undifferentiated buzz.
     */
    fun toOnOffTimings(pattern: HapticPattern): LongArray? {
        val waveform = toWaveform(pattern) ?: return null
        val on = waveform.amplitudes.map { it >= 128 }

        val timings = mutableListOf<Long>()
        var current = false   // waveform timings always start with an "off" entry by convention
        var run = 0L
        for (value in on) {
            if (value == current) {
                run += STEP_MS
            } else {
                timings += run
                current = value
                run = STEP_MS.toLong()
            }
        }
        timings += run
        return if (timings.size <= 1) null else timings.toLongArray()
    }
}
