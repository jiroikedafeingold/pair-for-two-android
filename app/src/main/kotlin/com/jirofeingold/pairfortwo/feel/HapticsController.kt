package com.jirofeingold.pairfortwo.feel

import android.content.Context
import android.os.Build
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.jirofeingold.pairfortwo.core.SkunkLevel

/**
 * Plays the game's haptics — port of iOS's `GameFeedback` haptic half plus `WinHaptics`,
 * `LoseHaptics` and `DragTickHaptics`.
 *
 * The patterns live in [HapticPatterns] and the translation in [HapticRenderer]; this class is only
 * the device end: capability probing, the degradation ladder, and the two settings that gate it.
 *
 * ## The degradation ladder
 *
 * Probed once at construction, exactly as StarBattleAndroid does it, because the answer never
 * changes for a given device and probing per-play would cost a binder call on every card:
 *
 * 1. Primitives supported → play all-transient patterns as compositions (crisp, tuned taps).
 * 2. Amplitude control → render everything as an amplitude waveform.
 * 3. Neither → on/off timings.
 * 4. No vibrator → silent.
 *
 * ## Two toggles, not one
 *
 * The app's own "Haptics" setting, **and** the system-wide `HAPTIC_FEEDBACK_ENABLED`. iOS has no
 * equivalent of the latter, but on Android a user who has turned haptics off system-wide means it,
 * and an app that buzzes anyway is misbehaving.
 */
class HapticsController(context: Context) {

    private val appContext = context.applicationContext

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }?.takeIf { it.hasVibrator() }

    /** Set by the settings screen; read at each call, exactly as iOS reads its UserDefaults key. */
    @Volatile
    var enabled: Boolean = true

    private val supportsAmplitude: Boolean = vibrator?.hasAmplitudeControl() == true

    private val supportsPrimitives: Boolean = run {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@run false
        val v = vibrator ?: return@run false
        val ids = intArrayOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
        )
        v.areAllPrimitivesSupported(*ids)
    }

    /**
     * Honours the system-wide setting on top of the app's own.
     *
     * `HAPTIC_FEEDBACK_ENABLED` is marked deprecated, but it is still the only way to *read* the
     * user's system-wide choice. The suggested alternative — `View.performHapticFeedback` — honours
     * it for you, which is no use when the effect is a custom multi-second pattern rather than one
     * of the platform constants. Reading it explicitly is the lesser evil against buzzing at
     * someone who has turned haptics off.
     */
    private val systemHapticsOn: Boolean
        @Suppress("DEPRECATION")
        get() = Settings.System.getInt(
            appContext.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0

    private fun shouldPlay(): Boolean = enabled && vibrator != null && systemHapticsOn

    // ---- Public API, mirroring the iOS entry points ----

    fun play(action: HapticPatterns.Action) = play(HapticPatterns.forAction(action))

    fun playWin(skunk: SkunkLevel) = play(HapticPatterns.win(skunk))

    fun playLose() = play(HapticPatterns.lose())

    /** A score tap whose weight scales with the points — see [HapticPatterns.scoreTick]. */
    fun scoreTick(points: Int) = play(HapticPatterns.scoreTick(points))

    /** @param progress 0…1 position of the value along the points slider. */
    fun tick(progress: Double) = play(HapticPatterns.sliderTick(progress))


    /**
     * Every effect is played as [VibrationAttributes.USAGE_TOUCH].
     *
     * Without attributes the platform files a vibration under `USAGE_UNKNOWN`, which is not just
     * untidy: the per-usage intensity sliders in Settings and some OEM power-saving rules key off
     * it, so an unattributed buzz can be scaled to nothing on a device where touch feedback is
     * perfectly well enabled. Below API 33 the same thing is said with `AudioAttributes`.
     */
    private fun Vibrator.play(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    fun cancel() {
        runCatching { vibrator?.cancel() }
    }

    // ---- The ladder ----

    private fun play(pattern: HapticPattern) {
        if (!shouldPlay()) return
        val v = vibrator ?: return

        if (supportsPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val steps = HapticRenderer.toComposition(pattern)
            if (steps != null) {
                runCatching {
                    val composition = VibrationEffect.startComposition()
                    for (step in steps) {
                        composition.addPrimitive(step.primitive.toAndroid(), step.scale, step.delayMs)
                    }
                    v.play(composition.compose())
                }.onSuccess { return }
            }
        }

        if (supportsAmplitude) {
            val waveform = HapticRenderer.toWaveform(pattern)
            if (waveform != null) {
                runCatching {
                    v.play(VibrationEffect.createWaveform(waveform.timings, waveform.amplitudes, -1))
                }.onSuccess { return }
            }
        }

        val timings = HapticRenderer.toOnOffTimings(pattern) ?: return
        runCatching { v.play(VibrationEffect.createWaveform(timings, -1)) }
    }

    private fun HapticRenderer.Primitive.toAndroid(): Int = when (this) {
        HapticRenderer.Primitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
        HapticRenderer.Primitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
        HapticRenderer.Primitive.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        HapticRenderer.Primitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
        HapticRenderer.Primitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
    }
}
