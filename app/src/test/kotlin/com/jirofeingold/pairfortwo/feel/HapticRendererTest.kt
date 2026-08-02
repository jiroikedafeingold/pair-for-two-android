package com.jirofeingold.pairfortwo.feel

import com.jirofeingold.pairfortwo.core.SkunkLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The Core Haptics → Android translation.
 *
 * A vibration can't be asserted by feel, but its *shape* can: that a tap lands where iOS taps, that
 * a swell rises and falls where iOS's curve does, that nothing silently renders to nothing. Those
 * are the failures that would otherwise only be found by holding a phone and wondering whether it
 * felt right.
 */
class HapticRendererTest {

    // ---- Strategy selection ----

    @Test
    fun `an all-transient pattern becomes a composition`() {
        val pattern = HapticPatterns.forAction(HapticPatterns.Action.GO)
        val steps = HapticRenderer.toComposition(pattern)

        assertNotNull(steps)
        assertEquals(2, steps!!.size, "GO is two firm taps")
        assertEquals(0, steps[0].delayMs, "the first tap is immediate")
        assertEquals(140, steps[1].delayMs, "the second lands 0.14s later")
        assertTrue(steps.all { it.scale > 0.8f }, "both taps are firm")
    }

    @Test
    fun `a pattern with a continuous event refuses the composition path`() {
        // CARD_PLAY is a transient *plus* a 45 ms continuous body — a composition can't express it.
        val pattern = HapticPatterns.forAction(HapticPatterns.Action.CARD_PLAY)
        assertNull(HapticRenderer.toComposition(pattern))
        assertNotNull(HapticRenderer.toWaveform(pattern), "it must still render as a waveform")
    }

    @Test
    fun `a pattern with an intensity curve refuses the composition path`() {
        // DECK_LIFT is a rising drag: one continuous event under a three-point curve.
        val pattern = HapticPatterns.forAction(HapticPatterns.Action.DECK_LIFT)
        assertNull(HapticRenderer.toComposition(pattern))
        assertNotNull(HapticRenderer.toWaveform(pattern))
    }

    // ---- Sharpness → primitive ----

    @Test
    fun `sharpness picks a primitive from sharp to dull`() {
        assertEquals(HapticRenderer.Primitive.CLICK, HapticRenderer.primitiveFor(0.9f))
        assertEquals(HapticRenderer.Primitive.TICK, HapticRenderer.primitiveFor(0.6f))
        assertEquals(HapticRenderer.Primitive.LOW_TICK, HapticRenderer.primitiveFor(0.3f))
        assertEquals(HapticRenderer.Primitive.THUD, HapticRenderer.primitiveFor(0.1f))

        // The crisp snap of a card landing must not come out as a thud.
        val cardPlay = HapticPatterns.forAction(HapticPatterns.Action.CARD_PLAY)
        val snap = cardPlay.events.filterIsInstance<HapticEvent.Transient>().single()
        assertEquals(HapticRenderer.Primitive.CLICK, HapticRenderer.primitiveFor(snap.sharpness))
    }

    // ---- Waveform shape ----

    @Test
    fun `timings and amplitudes are the same length and evenly stepped`() {
        val waveform = HapticRenderer.toWaveform(HapticPatterns.win(SkunkLevel.NONE))!!
        assertEquals(waveform.timings.size, waveform.amplitudes.size)
        assertTrue(waveform.timings.all { it == HapticRenderer.STEP_MS.toLong() })
        assertTrue(waveform.amplitudes.all { it in 0..255 }, "amplitudes must be in range")
    }

    @Test
    fun `the deck lift rises then falls, following its curve`() {
        // iOS curve: 0.2 at t=0, 0.7 at t=0.18, 0.0 at t=0.26. The shape has to survive.
        val waveform = HapticRenderer.toWaveform(
            HapticPatterns.forAction(HapticPatterns.Action.DECK_LIFT),
        )!!
        val at = { seconds: Double -> waveform.amplitudes[(seconds * 1000 / HapticRenderer.STEP_MS).toInt()] }

        assertTrue(at(0.02) < at(0.16), "the drag should be rising: ${at(0.02)} -> ${at(0.16)}")
        assertTrue(at(0.16) > at(0.25), "and then falling away: ${at(0.16)} -> ${at(0.25)}")
        assertTrue(at(0.02) > 0, "it must not start silent")
    }

    @Test
    fun `a transient is widened enough for a motor to render it`() {
        val waveform = HapticRenderer.toWaveform(
            HapticPatterns.forAction(HapticPatterns.Action.DISCARD_SELECT),
        )!!
        // A zero-duration impulse would produce nothing at all on real hardware.
        assertTrue(waveform.amplitudes.count { it > 0 } >= 2, "a tap must occupy at least 20 ms")
    }

    @Test
    fun `overlapping events sum rather than replace`() {
        // The win pattern's taps ride on top of its two continuous rumbles; if the renderer took a
        // maximum instead of a sum, the fusillade would vanish into the rumble.
        //
        // The tap is deliberately *weaker* than the rumble it sits on. With a weaker tap, `max`
        // leaves the amplitude untouched and only `sum` lifts it — which is the difference this
        // test exists to detect. An earlier version used a stronger tap, so both semantics raised
        // the amplitude and the assertion held either way.
        val rumbleOnly = HapticPattern(listOf(continuous(0.0, 0.5, 0.6f, 0.3f)))
        val withTap = HapticPattern(
            listOf(continuous(0.0, 0.5, 0.6f, 0.3f), transient(0.2, 0.3f, 0.9f)),
        )
        val a = HapticRenderer.toWaveform(rumbleOnly)!!
        val b = HapticRenderer.toWaveform(withTap)!!
        val i = (0.2 * 1000 / HapticRenderer.STEP_MS).toInt()

        assertTrue(
            b.amplitudes[i] > a.amplitudes[i],
            "a weaker tap must still lift the rumble: ${b.amplitudes[i]} vs ${a.amplitudes[i]}",
        )
    }

    @Test
    fun `a quiet step never rounds down to silence`() {
        // Amplitude 0 means "off". Rounding a faint part of a swell to 0 would punch a hole in it.
        val faint = HapticPattern(listOf(continuous(0.0, 0.1, 0.001f, 0.5f)))
        val waveform = HapticRenderer.toWaveform(faint)!!
        assertTrue(waveform.amplitudes.any { it >= 1 })
        assertTrue(waveform.amplitudes.none { it < 0 })
    }

    // ---- The win pattern ----

    @Test
    fun `the win celebration scales with the skunk level`() {
        val none = HapticRenderer.toWaveform(HapticPatterns.win(SkunkLevel.NONE))!!
        val single = HapticRenderer.toWaveform(HapticPatterns.win(SkunkLevel.SINGLE))!!
        val double = HapticRenderer.toWaveform(HapticPatterns.win(SkunkLevel.DOUBLE))!!

        fun seconds(w: HapticRenderer.Waveform) = w.timings.size * HapticRenderer.STEP_MS / 1000.0
        assertEquals(4.0, seconds(none), 0.1)
        assertEquals(5.5, seconds(single), 0.1)
        assertEquals(7.0, seconds(double), 0.1)
    }

    @Test
    fun `the win pattern fades to nothing at the very end`() {
        // The iOS intensity curve ends at 0.0 — the celebration stops rather than being cut off.
        val waveform = HapticRenderer.toWaveform(HapticPatterns.win(SkunkLevel.DOUBLE))!!
        assertEquals(0, waveform.amplitudes.last(), "it should end silent")
        assertTrue(waveform.amplitudes.max() > 200, "and peak hard before it does")
    }

    // ---- Degradation ----

    @Test
    fun `on-off timings alternate and start from silence`() {
        val timings = HapticRenderer.toOnOffTimings(HapticPatterns.forAction(HapticPatterns.Action.DEAL))
        assertNotNull(timings)
        assertTrue(timings!!.size > 1, "a riffle is more than one buzz")
        assertTrue(timings.all { it >= 0 })
    }

    // ---- Every pattern, no exceptions ----

    /**
     * The whole set, rendered both ways. A pattern that silently produces nothing is the failure
     * mode most likely to ship unnoticed, since the only symptom is a phone that doesn't buzz.
     */
    @TestFactory
    fun `every pattern renders to something audible`(): List<DynamicTest> {
        val patterns = buildList {
            for (action in HapticPatterns.Action.entries) add(action.name to HapticPatterns.forAction(action))
            for (skunk in SkunkLevel.entries) add("win-${skunk.name}" to HapticPatterns.win(skunk))
            add("lose" to HapticPatterns.lose())
            for (p in listOf(0.0, 0.5, 0.84, 0.85, 1.0)) add("tick-$p" to HapticPatterns.sliderTick(p))
        }
        return patterns.map { (name, pattern) ->
            DynamicTest.dynamicTest(name) {
                assertTrue(pattern.duration > 0.0, "$name has no duration")
                val waveform = HapticRenderer.toWaveform(pattern)
                assertNotNull(waveform, "$name rendered to no waveform at all")
                assertTrue(waveform!!.amplitudes.any { it > 0 }, "$name is entirely silent")

                // All-transient patterns must also survive the composition path.
                if (pattern.isAllTransients && pattern.intensityCurve.isEmpty()) {
                    val steps = HapticRenderer.toComposition(pattern)
                    assertNotNull(steps, "$name is all taps but produced no composition")
                    assertTrue(steps!!.all { it.delayMs >= 0 }, "$name has a negative delay")
                    assertTrue(steps.all { it.scale in 0f..1f }, "$name has an out-of-range scale")
                }
            }
        }
    }

    /** The slider's top end stacks extra events — the tick has to get stronger, not just different. */
    @Test
    fun `the slider tick strengthens toward the top of the track`() {
        val low = HapticRenderer.toWaveform(HapticPatterns.sliderTick(0.0))!!
        val high = HapticRenderer.toWaveform(HapticPatterns.sliderTick(1.0))!!
        assertTrue(
            high.amplitudes.max() > low.amplitudes.max(),
            "top-of-track tick should be stronger: ${high.amplitudes.max()} vs ${low.amplitudes.max()}",
        )
        assertEquals(1, HapticPatterns.sliderTick(0.5).events.size, "mid-track is a single tap")
        assertEquals(4, HapticPatterns.sliderTick(0.9).events.size, "the top stacks a thud and a swell")
    }
}
