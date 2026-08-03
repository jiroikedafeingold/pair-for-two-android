package com.jirofeingold.pairfortwo.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jirofeingold.pairfortwo.core.GamePersistence
import com.jirofeingold.pairfortwo.core.GameState
import com.jirofeingold.pairfortwo.core.PlayerID
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Persists an interrupted game so it can be rejoined after the app is closed — the Android form of
 * iOS's `Persistence.swift` (PLAN.md §7).
 *
 * The semantics are iOS's, exactly:
 * - The **host** writes its full authoritative [GameState] to a file, so resuming is a matter of
 *   reloading that one object and re-hosting.
 * - Both devices write a small **marker** recording that a game is in progress, this device's role
 *   and a score summary, so *either* phone can offer "Rejoin game".
 * - A guest **deletes** any state file it still holds, so the file reliably identifies the one true
 *   host at resume time — which is what stops both phones trying to host each other.
 *
 * ## Why everything goes through one queue
 *
 * [GamePersistence]'s methods are synchronous fire-and-forget, because the view model calls them
 * from the middle of applying a game message. Android's storage is not: a file write shouldn't
 * happen on the main thread, and DataStore is suspend-only. Launching a coroutine per call would
 * leave their completion order undefined — a `clear()` racing the `save()` before it could resurrect
 * a finished game as a phantom "Rejoin game".
 *
 * So every operation is enqueued on [ops] and applied by a single writer coroutine, in order, off
 * the main thread. Reads go through the same queue, so the menu can never observe a half-applied
 * state either. PLAN.md §7 puts the marker in DataStore and this keeps it there; the queue is what
 * makes that safe.
 *
 * The state is **encoded on the caller's thread**, deliberately: `GameState` is mutable and the
 * engine keeps mutating it, so deferring the encode to the writer would serialise whatever the game
 * had become by then rather than the moment that was saved.
 *
 * The saved file is Kotlin's `@Serializable` encoding of `GameState`, which is not iOS's. That's
 * fine and always will be: this file never leaves the device, and only the redacted snapshots on the
 * wire have to agree between platforms (PLAN.md §2).
 */
class AndroidGamePersistence(
    context: Context,
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
) : GamePersistence {

    /** A game waiting to be rejoined. */
    data class Resume(
        /** The role this device recorded. Informational — [hasState] is what actually decides. */
        val isHost: Boolean,
        /** "Ada 42 · Bo 51", for the menu button. */
        val summary: String,
        /**
         * This device holds the authoritative state, so it must be the one to re-host. Trusted over
         * [isHost] because a marker can go stale, and two hosts deadlock a rejoin.
         */
        val hasState: Boolean,
    )

    private val appContext = context.applicationContext
    private val stateFile = File(appContext.filesDir, FILENAME)
    private val tempFile = File(appContext.filesDir, "$FILENAME.tmp")

    private sealed interface Op {
        class Save(val encoded: String, val summary: String) : Op
        class Marker(val isHost: Boolean, val summary: String) : Op
        data object Clear : Op
        class ReadResume(val reply: CompletableDeferred<Resume?>) : Op
        class ReadState(val reply: CompletableDeferred<GameState?>) : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)

    init {
        scope.launch(io) {
            for (op in ops) runCatching { apply(op) }
        }
    }

    // ---- GamePersistence ----

    override fun save(state: GameState) {
        val encoded = runCatching { json.encodeToString(GameState.serializer(), state) }.getOrNull()
            ?: return
        ops.trySend(Op.Save(encoded, summaryOf(state)))
    }

    override fun saveMarker(isHost: Boolean, summary: String) {
        ops.trySend(Op.Marker(isHost, summary))
    }

    override fun clear() {
        ops.trySend(Op.Clear)
    }

    // ---- Reads ----

    /** The game waiting to be rejoined, or null if there isn't one. */
    suspend fun resume(): Resume? = CompletableDeferred<Resume?>()
        .also { ops.send(Op.ReadResume(it)) }
        .await()

    /** The host's saved game. Null on a guest, or if the file is missing or unreadable. */
    suspend fun loadState(): GameState? = CompletableDeferred<GameState?>()
        .also { ops.send(Op.ReadState(it)) }
        .await()

    // ---- The single writer ----

    private suspend fun apply(op: Op) {
        when (op) {
            is Op.Save -> {
                writeStateFile(op.encoded)
                writeMarker(isHost = true, summary = op.summary)
            }

            is Op.Marker -> {
                // A guest holds no state, so drop any file left from a game it once hosted.
                if (!op.isHost) deleteState()
                writeMarker(op.isHost, op.summary)
            }

            is Op.Clear -> {
                deleteState()
                appContext.resumeStore.edit { it.clear() }
            }

            is Op.ReadResume -> {
                val prefs = appContext.resumeStore.data
                    .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                    .first()
                op.reply.complete(
                    if (prefs[Keys.ACTIVE] != true) {
                        null
                    } else {
                        Resume(
                            isHost = prefs[Keys.IS_HOST] ?: false,
                            summary = prefs[Keys.SUMMARY].orEmpty(),
                            hasState = stateFile.exists(),
                        )
                    },
                )
            }

            is Op.ReadState -> op.reply.complete(readStateFile())
        }
    }

    /** Written to a sibling and renamed, so a kill mid-write can't leave a truncated game behind. */
    private fun writeStateFile(encoded: String) {
        runCatching {
            tempFile.writeText(encoded)
            if (!tempFile.renameTo(stateFile)) {
                stateFile.writeText(encoded)
                tempFile.delete()
            }
        }.onFailure { tempFile.delete() }
    }

    private fun readStateFile(): GameState? {
        if (!stateFile.exists()) return null
        return runCatching {
            json.decodeFromString(GameState.serializer(), stateFile.readText())
        }.getOrNull()
    }

    private fun deleteState() {
        runCatching { stateFile.delete() }
        runCatching { tempFile.delete() }
    }

    private suspend fun writeMarker(isHost: Boolean, summary: String) {
        runCatching {
            appContext.resumeStore.edit { prefs ->
                prefs[Keys.ACTIVE] = true
                prefs[Keys.IS_HOST] = isHost
                prefs[Keys.SUMMARY] = summary
            }
        }
    }

    private fun summaryOf(state: GameState): String {
        val one = state.names[PlayerID.ONE] ?: "Player 1"
        val two = state.names[PlayerID.TWO] ?: "Player 2"
        val oneScore = state.scores[PlayerID.ONE] ?: 0
        val twoScore = state.scores[PlayerID.TWO] ?: 0
        return "$one $oneScore · $two $twoScore"
    }

    private object Keys {
        val ACTIVE = booleanPreferencesKey("resume.active")
        val IS_HOST = booleanPreferencesKey("resume.isHost")
        val SUMMARY = stringPreferencesKey("resume.summary")
    }

    private companion object {
        const val FILENAME = "pairfortwo-game.json"

        /**
         * Lenient about unknown keys so a state file written by a *newer* build — one that added a
         * field — still loads here rather than throwing away a game in progress.
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}

private val Context.resumeStore: DataStore<Preferences> by preferencesDataStore(name = "resume")
