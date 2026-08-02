package com.jirofeingold.pairfortwo.core

/**
 * Saving and resuming a game — the seam for what iOS does in `Persistence.swift`.
 *
 * Declared here as an interface rather than implemented, because the real storage is Android's
 * (a file in `filesDir` for the host's state, DataStore for the resume marker) and that is
 * PLAN.md §10 phase 7. [GameViewModel] calls it at exactly the points the Swift does, so phase 7 is
 * a matter of supplying an implementation rather than finding the call sites again.
 *
 * The semantics that matter, carried over from iOS:
 * - Only the **host** writes full state; a guest writes a marker so it can offer to rejoin.
 * - A guest **deletes** any stale state file, so `hasSavedState` reliably identifies the true host.
 * - Game over clears everything.
 */
interface GamePersistence {

    /** The host's authoritative state, written on every change and on backgrounding. */
    fun save(state: GameState)

    /** A guest's "there is a game to rejoin" marker plus a human-readable score summary. */
    fun saveMarker(isHost: Boolean, summary: String)

    /** Forget any saved game — called at game over and on quit. */
    fun clear()

    /**
     * Does nothing. The default until phase 7 lands, so the view model can be built and tested
     * without a storage layer, and so tests aren't forced to stub one.
     */
    object None : GamePersistence {
        override fun save(state: GameState) = Unit
        override fun saveMarker(isHost: Boolean, summary: String) = Unit
        override fun clear() = Unit
    }
}
