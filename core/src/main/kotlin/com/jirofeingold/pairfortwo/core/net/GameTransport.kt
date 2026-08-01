package com.jirofeingold.pairfortwo.core.net

import com.jirofeingold.pairfortwo.core.GameMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Connection lifecycle plus inbound messages, surfaced as a single stream — port of the iOS
 * `TransportEvent`.
 */
sealed interface TransportEvent {
    data object Connected : TransportEvent
    data object Reconnecting : TransportEvent
    data object Disconnected : TransportEvent
    data class Received(val message: GameMessage) : TransportEvent
}

/**
 * Abstraction over how the two devices talk — port of the iOS `GameTransport` protocol.
 *
 * iOS's `AsyncStream` becomes a [Flow] and its `@MainActor` isolation becomes a caller-supplied
 * `CoroutineScope`, but the contract is otherwise identical so the two apps' view models can be
 * read side by side.
 */
interface GameTransport {

    /** Whether this device owns the authoritative `GameState` and runs the engine. */
    val isHost: Boolean

    /**
     * Connection events and inbound messages.
     *
     * Single-consumer and unbounded, matching iOS's `AsyncStream(bufferingPolicy: .unbounded)`:
     * a burst of snapshots during a reconnect must not be silently dropped because the collector
     * was briefly busy.
     */
    val events: Flow<TransportEvent>

    /** Sends to the peer. On the host that is usually a snapshot; on a guest, an intent. */
    suspend fun send(message: GameMessage)

    /**
     * Ask the transport to re-establish the connection, e.g. after returning from the background.
     *
     * [force] rebuilds even if the transport still *believes* it is connected. That is not
     * belt-and-braces: after a background/foreground cycle the OS routinely reports a dead socket
     * as live for tens of seconds, and waiting out the TCP timeout is worse than rebuilding.
     */
    fun reconnect(force: Boolean = false)
}

/**
 * A same-room transport that the connect screen drives directly — port of iOS's `NearbyTransport`.
 *
 * On iOS two of these run side by side (Multipeer for iOS↔iOS, LAN for iOS↔Android) and the
 * connect screen merges their discovery. Android has only [LanTransport], but the abstraction is
 * kept so the two connect screens stay recognisably the same shape, and so a future BLE transport
 * has somewhere to land.
 */
interface NearbyTransport : GameTransport {

    /** Settable because a resume decides the host by who holds the saved state, not by a tap. */
    override var isHost: Boolean

    val phase: StateFlow<TransportPhase>

    /** Hosts found while browsing, ready for the join list. */
    val discoveredPeers: StateFlow<List<LanPeer>>

    /** The peer we are connected (or connecting) to, for the connect screen's status line. */
    val connectedPeerName: StateFlow<String?>

    fun startHosting()

    fun startBrowsing()

    fun invite(peer: LanPeer)

    /** Tear down discovery and any live connection. */
    fun stop()
}

/** Where a [NearbyTransport] is in its connect lifecycle. Mirrors iOS's `LANTransport.Phase`. */
enum class TransportPhase {
    IDLE,
    HOSTING,
    BROWSING,
    CONNECTING,
    CONNECTED,
    RECONNECTING,

    /**
     * Terminal for this attempt. On Android this is the state the connect screen must turn into a
     * real "couldn't reach the other device — check you're both on the same Wi-Fi" message, since
     * AP isolation on public networks silently blocks peer-to-peer traffic entirely (PLAN.md §11).
     */
    DISCONNECTED,
}
