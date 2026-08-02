package com.jirofeingold.pairfortwo.feel

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import com.jirofeingold.pairfortwo.R
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The game's sound effects, played through [SoundPool].
 *
 * The nine WAVs in `res/raw` are **rendered from the iOS app's own synthesis code**
 * (`tools/render-sounds.sh` in the iOS repo, compiled against `SoundSynthesis.swift`), so the two
 * apps are not merely similar here — they are sample-for-sample identical. iOS synthesizes at
 * launch; Android ships the files, which costs nothing at runtime and nothing at startup.
 *
 * ## One deliberate difference from iOS
 *
 * iOS uses an `.ambient` audio session, so the ring/silent switch mutes effects. Android has no
 * such switch, and games conventionally play through the game stream, so **only the in-app
 * "Sound effects" toggle gates playback** here. Same call StarBattleAndroid made. The toggle is
 * read at each play, so turning it off silences everything immediately.
 */
class SoundEffects(
    context: Context,
    private val scope: CoroutineScope,
) {

    /** The effect set, matching `SoundSynthesis.allEffects` on the iOS side one for one. */
    enum class Effect(@param:RawRes val res: Int) {
        CLICK(R.raw.click),
        TICK(R.raw.tick),
        FLIP(R.raw.flip),
        WHOOSH(R.raw.whoosh),
        RIFFLE(R.raw.riffle),
        DING(R.raw.ding),
        CHIME(R.raw.chime),
        GO(R.raw.go),
        FIREWORK(R.raw.firework),
    }

    /** Set by the settings screen; read at each play, exactly as iOS reads its UserDefaults key. */
    @Volatile
    var enabled: Boolean = true

    private val pool = SoundPool.Builder()
        // Eight is enough for the celebration volley to overlap without truncating itself.
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val soundIds = mutableMapOf<Effect, Int>()

    /**
     * Which sounds have finished decoding. `SoundPool.load` is asynchronous and playing an
     * unloaded id is silently a no-op, so early taps would otherwise vanish — which reads as
     * "the sound is broken" rather than "the sound wasn't ready".
     */
    private val loaded = mutableSetOf<Int>()

    private var celebrationJob: Job? = null

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(loaded) { loaded += sampleId }
        }
        for (effect in Effect.entries) {
            soundIds[effect] = pool.load(context.applicationContext, effect.res, 1)
        }
    }

    /** Plays [effect] once at full volume. Silently does nothing if sound is off or not yet loaded. */
    fun play(effect: Effect, rate: Float = 1f, volume: Float = 1f) {
        if (!enabled) return
        val id = soundIds[effect] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        pool.play(id, volume, volume, /* priority = */ 1, /* loop = */ 0, rate)
    }

    /**
     * The win celebration: fourteen firework pops with the same randomised rate and volume iOS
     * uses, spaced the same way. `SoundPool.play` takes both parameters, so this ports exactly.
     *
     * Sound only — the haptic side of the celebration is [HapticsController]'s.
     */
    fun playCelebration() {
        if (!enabled) return
        celebrationJob?.cancel()
        celebrationJob = scope.launch {
            repeat(CELEBRATION_POPS) {
                play(
                    Effect.FIREWORK,
                    rate = Random.nextDouble(0.88, 1.22).toFloat(),
                    volume = Random.nextDouble(0.65, 1.0).toFloat(),
                )
                delay((Random.nextDouble(0.26, 0.6) * 1000).toLong())
            }
        }
    }

    fun stopCelebration() {
        celebrationJob?.cancel()
        celebrationJob = null
    }

    /** Release the pool. After this the instance is dead and must not be reused. */
    fun release() {
        stopCelebration()
        pool.release()
    }

    private companion object {
        const val MAX_STREAMS = 8
        const val CELEBRATION_POPS = 14
    }
}
