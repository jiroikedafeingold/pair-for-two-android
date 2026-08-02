package com.jirofeingold.pairfortwo.feel

import android.content.Context
import com.jirofeingold.pairfortwo.core.SkunkLevel
import kotlinx.coroutines.CoroutineScope

/**
 * Unified tactile + audio feedback — port of the iOS `GameFeedback`.
 *
 * The eleven actions live on [HapticPatterns.Action] rather than being re-declared here: Kotlin
 * doesn't allow a type alias inside a class body, and a second enum that had to be kept in step
 * with the first would be worse than the small amount of qualification this costs.
 *
 * One call, `feedback.play(Action.CARD_PLAY)`, fires both the haptic and the matching sound, so the
 * game code never has to think about the two separately. Deliberately the same shape as the iOS
 * entry point, including the action list and the action → sound mapping, so the two apps' call
 * sites read identically.
 */
class GameFeedback(
    context: Context,
    scope: CoroutineScope,
) {

    val sounds = SoundEffects(context, scope)
    val haptics = HapticsController(context)

    /** Settings → "Sound effects". */
    var soundEnabled: Boolean
        get() = sounds.enabled
        set(value) { sounds.enabled = value }

    /** Settings → "Haptics". */
    var hapticsEnabled: Boolean
        get() = haptics.enabled
        set(value) { haptics.enabled = value }

    fun play(action: HapticPatterns.Action) {
        haptics.play(action)
        sounds.play(soundFor(action))
    }

    /** The win: the celebration volley plus the long rumble, scaled by how badly they lost. */
    fun playWin(skunk: SkunkLevel) {
        haptics.playWin(skunk)
        sounds.playCelebration()
    }

    /** The loss: subdued haptic, no sound — matching iOS, which plays nothing here. */
    fun playLose() {
        haptics.playLose()
    }

    /** Per-step feedback while dragging the points slider. Haptic only, as on iOS. */
    fun sliderTick(progress: Double) {
        haptics.tick(progress)
    }

    fun stopCelebration() {
        sounds.stopCelebration()
        haptics.cancel()
    }

    fun release() {
        sounds.release()
        haptics.cancel()
    }

    /** The action → sound mapping, identical to iOS's `soundKey(_:)`. */
    private fun soundFor(action: HapticPatterns.Action): SoundEffects.Effect = when (action) {
        HapticPatterns.Action.CARD_PLAY -> SoundEffects.Effect.CLICK
        HapticPatterns.Action.DISCARD_SELECT -> SoundEffects.Effect.TICK
        HapticPatterns.Action.DISCARD_CONFIRM -> SoundEffects.Effect.CLICK
        HapticPatterns.Action.CUT_TAP -> SoundEffects.Effect.FLIP
        HapticPatterns.Action.DECK_LIFT -> SoundEffects.Effect.WHOOSH
        HapticPatterns.Action.STARTER_REVEAL -> SoundEffects.Effect.FLIP
        HapticPatterns.Action.DEAL -> SoundEffects.Effect.RIFFLE
        HapticPatterns.Action.GO -> SoundEffects.Effect.GO
        HapticPatterns.Action.THIRTY_ONE -> SoundEffects.Effect.CHIME
        HapticPatterns.Action.SCORE -> SoundEffects.Effect.DING
        HapticPatterns.Action.ADVANCE -> SoundEffects.Effect.TICK
    }
}
