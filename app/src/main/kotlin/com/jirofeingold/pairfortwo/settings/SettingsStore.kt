package com.jirofeingold.pairfortwo.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jirofeingold.pairfortwo.core.ScoringMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The player's settings — the Android form of the iOS `@AppStorage` keys read by `SettingsView`.
 *
 * The names and stored types match iOS deliberately (PLAN.md §7): `cardBackID` 1 means Celestial on
 * both platforms, `scoringMode` is the same Int raw value the wire protocol uses. Anything that
 * crosses to the other player — the scoring mode — still travels as a game message; this is only
 * where *this* device remembers its own preferences.
 *
 * Not here yet, because the screen that owns it isn't ported: `replayBeforeWin` (ScoringReplay).
 */
data class AppSettings(
    /**
     * Scoring mode. [ScoringMode.OFF] — count it yourself — matching iOS's stored default.
     *
     * Android used to start in [ScoringMode.FEEDBACK], to suit the scaffolding game the app dropped
     * into before there was a menu. Now that a game starts from the menu the two apps should agree,
     * and a new player meets the same default on either. iOS explains the choice during onboarding;
     * until that screen is ported, Settings is the only place it's introduced.
     */
    val scoringMode: ScoringMode = ScoringMode.OFF,
    /** "Confirm after release": the slider stages an amount for the +N button instead of scoring. */
    val confirmRelease: Boolean = true,
    val cardBackID: Int = 0,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val celebrationEffects: Boolean = true,
    val scoreTrackEnabled: Boolean = true,
    /** How the other player sees you. Trimmed and defaulted before it goes anywhere — see [player]. */
    val localName: String = "Player",
    /** Index into the twelve player themes; your peg's colour. */
    val localColorID: Int = 1,
    /** The first-run walkthrough has been seen. Nothing reads it until OnboardingScreen lands. */
    val hasOnboarded: Boolean = false,
) {
    /**
     * The name to actually use: trimmed, and never blank.
     *
     * A blank name would advertise an empty Bonjour service and leave the other player picking from
     * a list of nameless rows, so the fallback matters more here than it looks. iOS does the same
     * trim in `RootView.playerName`.
     */
    val player: String get() = localName.trim().ifEmpty { "Player" }
}

private val Context.preferences: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    /**
     * Emits the current settings, and again on every change.
     *
     * A read failure (a corrupt or unreadable file) falls back to defaults rather than cancelling
     * the flow: settings are preferences, and losing them should never take the game down with it.
     */
    val settings: Flow<AppSettings> = context.preferences.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    /** Read-modify-write of the whole record, so a setter can never half-apply a change. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.preferences.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.SCORING_MODE] = next.scoringMode.value
            prefs[Keys.CONFIRM_RELEASE] = next.confirmRelease
            prefs[Keys.CARD_BACK_ID] = next.cardBackID
            prefs[Keys.HAPTICS_ENABLED] = next.hapticsEnabled
            prefs[Keys.SOUND_ENABLED] = next.soundEnabled
            prefs[Keys.CELEBRATION_EFFECTS] = next.celebrationEffects
            prefs[Keys.SCORE_TRACK_ENABLED] = next.scoreTrackEnabled
            prefs[Keys.LOCAL_NAME] = next.localName
            prefs[Keys.LOCAL_COLOR_ID] = next.localColorID
            prefs[Keys.HAS_ONBOARDED] = next.hasOnboarded
        }
    }

    private object Keys {
        val SCORING_MODE = intPreferencesKey("scoringMode")
        val CONFIRM_RELEASE = booleanPreferencesKey("confirmRelease")
        val CARD_BACK_ID = intPreferencesKey("cardBackID")
        val HAPTICS_ENABLED = booleanPreferencesKey("hapticsEnabled")
        val SOUND_ENABLED = booleanPreferencesKey("soundEnabled")
        val CELEBRATION_EFFECTS = booleanPreferencesKey("celebrationEffects")
        val SCORE_TRACK_ENABLED = booleanPreferencesKey("scoreTrackEnabled")
        val LOCAL_NAME = stringPreferencesKey("localName")
        val LOCAL_COLOR_ID = intPreferencesKey("localColorID")
        val HAS_ONBOARDED = booleanPreferencesKey("hasOnboarded")
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            scoringMode = this[Keys.SCORING_MODE]?.let(ScoringMode::of) ?: defaults.scoringMode,
            confirmRelease = this[Keys.CONFIRM_RELEASE] ?: defaults.confirmRelease,
            cardBackID = this[Keys.CARD_BACK_ID] ?: defaults.cardBackID,
            hapticsEnabled = this[Keys.HAPTICS_ENABLED] ?: defaults.hapticsEnabled,
            soundEnabled = this[Keys.SOUND_ENABLED] ?: defaults.soundEnabled,
            celebrationEffects = this[Keys.CELEBRATION_EFFECTS] ?: defaults.celebrationEffects,
            scoreTrackEnabled = this[Keys.SCORE_TRACK_ENABLED] ?: defaults.scoreTrackEnabled,
            localName = this[Keys.LOCAL_NAME] ?: defaults.localName,
            localColorID = this[Keys.LOCAL_COLOR_ID] ?: defaults.localColorID,
            hasOnboarded = this[Keys.HAS_ONBOARDED] ?: defaults.hasOnboarded,
        )
    }
}
