package com.jirofeingold.pairfortwo.core.net

import com.jirofeingold.pairfortwo.core.GameMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Single-process transport for pass-and-play on one device, and for tests — port of the iOS
 * `LoopbackTransport`.
 *
 * There is no peer: this device is the host and both players act on it. The host view model applies
 * intents straight to the engine and renders snapshots locally, so [send] has nothing to transmit.
 * [deliver] exists so tests can inject simulated peer messages through the same [events] path the
 * real transports use.
 */
class LoopbackTransport : GameTransport {

    override val isHost: Boolean = true

    private val channel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = channel.receiveAsFlow()

    init {
        channel.trySend(TransportEvent.Connected)
    }

    /** No peer to transmit to in single-process play; intents are applied locally by the host. */
    override suspend fun send(message: GameMessage) = Unit

    override fun reconnect(force: Boolean) = Unit

    /** Inject a message as though it arrived from a peer. */
    fun deliver(message: GameMessage) {
        channel.trySend(TransportEvent.Received(message))
    }

    /** End the event stream, e.g. when leaving the game. */
    fun finish() {
        channel.close()
    }
}
