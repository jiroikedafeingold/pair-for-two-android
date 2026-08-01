package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything that crosses the wire between the two devices.
 *
 * The host is the referee: guests send *intents*; the host validates, mutates the canonical
 * game state, and broadcasts a redacted [PlayerSnapshot]. Port of the iOS `GameMessage`, but
 * encoded per PROTOCOL.md rather than by either language's defaults — serialize with
 * [PairWireJson], whose `classDiscriminator = "t"` produces the `{"t":…}` envelope.
 */
@Serializable
sealed interface GameMessage {

    // ---- Handshake / lifecycle ----

    @Serializable
    @SerialName("hello")
    data class Hello(
        /** Always written, even though it equals the default — the peer uses it to detect v1. */
        val protocol: Int = WIRE_PROTOCOL_VERSION,
        val name: String,
        val colorID: Int,
        /** Lowercase canonical UUID string. */
        val playerToken: String,
    ) : GameMessage

    /** Host → guest: which player you are. */
    @Serializable
    @SerialName("assignSeat")
    data class AssignSeat(val player: PlayerID) : GameMessage

    /** Host → guest: the current redacted view. */
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val snapshot: PlayerSnapshot) : GameMessage

    // ---- Guest → host intents ----

    @Serializable
    @SerialName("intentCut")
    data class IntentCut(val index: Int) : GameMessage

    @Serializable
    @SerialName("intentDiscard")
    data class IntentDiscard(val cards: List<Card>) : GameMessage

    @Serializable
    @SerialName("intentPlay")
    data class IntentPlay(val card: Card) : GameMessage

    @Serializable
    @SerialName("intentGo")
    data object IntentGo : GameMessage

    /** The pone lifts the deck for the starter cut. */
    @Serializable
    @SerialName("intentLiftCut")
    data class IntentLiftCut(val index: Int) : GameMessage

    /** The dealer turns up the starter. */
    @Serializable
    @SerialName("intentRevealStarter")
    data object IntentRevealStarter : GameMessage

    @Serializable
    @SerialName("claimPoints")
    data class ClaimPoints(val player: PlayerID, val amount: Int) : GameMessage

    @Serializable
    @SerialName("undo")
    data class Undo(val player: PlayerID) : GameMessage

    /** "Continue" through cut-for-deal recut / show steps / next deal. */
    @Serializable
    @SerialName("advance")
    data object Advance : GameMessage

    @Serializable
    @SerialName("playAgain")
    data object PlayAgain : GameMessage

    // ---- Either direction ----

    /** Live name/colour change from Settings. */
    @Serializable
    @SerialName("updateIdentity")
    data class UpdateIdentity(val name: String, val colorID: Int) : GameMessage

    /** Live scoring-mode change from Settings. Carries the raw Int, matching iOS. */
    @Serializable
    @SerialName("setScoringMode")
    data class SetScoringMode(val mode: Int) : GameMessage

    /** Either side ends the game for both players. */
    @Serializable
    @SerialName("quitGame")
    data object QuitGame : GameMessage
}
