package com.jirofeingold.pairfortwo.core.net

import com.jirofeingold.pairfortwo.core.GameMessage
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cross-platform local transport: mDNS discovery plus a plain TCP socket carrying newline-delimited
 * JSON. Port of the iOS `LANTransport`, and the transport the two platforms share.
 *
 * MultipeerConnectivity is Apple-only, so an Android device can never join one — this is the path
 * that works across platforms. It needs both devices on the same Wi-Fi network (a personal hotspot
 * counts), which is the trade-off for working at all. See PLAN.md §4.
 *
 * ## What was carried over deliberately
 *
 * The behaviours below are not incidental; `MultipeerSession` acquired them from real failures, and
 * iOS's `LANTransport` re-applied them. Dropping any one of them produces a transport that works in
 * a quiet room and fails on a train:
 *
 * - **Outbox buffering** so a tap during a connectivity gap isn't lost, capped at 200.
 * - **Forced rebuild** on `reconnect(force = true)`, because after a background/foreground cycle the
 *   OS reports a dead socket as live for tens of seconds.
 * - **Ghost-peer collapsing by name**, since a relaunched host briefly appears twice while its old
 *   advertisement times out.
 * - **A guest retry loop** while reconnecting: the host may not be advertising again yet, so one
 *   browse pass is not enough.
 * - **Generation-stamped connections**, so a stale socket's reader can't resurrect itself after a
 *   reconnect has already swapped in a new one.
 *
 * Plus two that TCP needs and Multipeer didn't: partial reads reassembled across `read` calls
 * ([Ndjson.LineAssembler]), and `TCP_NODELAY`, because these are small latency-sensitive messages
 * and Nagle's algorithm would sit on them.
 *
 * Always speaks protocol v1: this transport is new, so it has no legacy peers.
 *
 * All state transitions are serialised on [lock]; blocking socket work runs on [io].
 */
class LanTransport(
    displayName: String,
    private val discovery: LanDiscovery,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How often a reconnecting guest re-checks discovery. Lowered by tests. */
    private val rebrowseIntervalMs: Long = 3_000L,
) : NearbyTransport {

    private val displayName: String =
        displayName.trim().ifEmpty { "Player" }.take(60)

    override var isHost: Boolean = false

    private val _phase = MutableStateFlow(TransportPhase.IDLE)
    override val phase: StateFlow<TransportPhase> = _phase.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<LanPeer>>(emptyList())
    override val discoveredPeers: StateFlow<List<LanPeer>> = _discoveredPeers.asStateFlow()

    private val _connectedPeerName = MutableStateFlow<String?>(null)
    override val connectedPeerName: StateFlow<String?> = _connectedPeerName.asStateFlow()

    /** Unbounded and single-consumer, matching iOS's `AsyncStream(bufferingPolicy: .unbounded)`. */
    private val eventChannel = Channel<TransportEvent>(Channel.UNLIMITED)
    override val events: Flow<TransportEvent> = eventChannel.receiveAsFlow()

    private val lock = Mutex()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var browseJob: Job? = null
    private var reconnectJob: Job? = null
    private var connection: Conn? = null

    /**
     * Once true a drop is treated as recoverable and we retry rather than going terminal — the same
     * rule as `MultipeerSession.didConnect`. Before the first successful connect, a failure means
     * "couldn't reach them at all", which the user needs told.
     */
    @Volatile
    private var didConnect = false

    /**
     * Buffered sends, flushed on the next connect.
     *
     * Holds already-framed bytes rather than messages so that frames still queued on a dying
     * connection can be moved back here verbatim — see [rebuildLocked].
     */
    private val outbox = ArrayDeque<ByteArray>()

    private var generation = 0

    // ---- Connect lifecycle ----

    override fun startHosting() {
        scope.launch {
            lock.withLock {
                isHost = true
                _phase.value = TransportPhase.HOSTING
                startListenerLocked()
            }
        }
    }

    override fun startBrowsing() {
        scope.launch {
            lock.withLock {
                isHost = false
                _phase.value = TransportPhase.BROWSING
                startBrowserLocked()
            }
        }
    }

    override fun invite(peer: LanPeer) {
        scope.launch {
            lock.withLock {
                _phase.value = TransportPhase.CONNECTING
                _connectedPeerName.value = peer.name
            }
            connectTo(peer)
        }
    }

    override fun stop() {
        scope.launch {
            lock.withLock {
                reconnectJob?.cancel(); reconnectJob = null
                browseJob?.cancel(); browseJob = null
                closeListenerLocked()
                connection?.close(); connection = null
                discovery.stopBrowsing()
                discovery.shutdown()
                _phase.value = TransportPhase.IDLE
            }
            eventChannel.close()
        }
    }

    private suspend fun startListenerLocked() {
        closeListenerLocked()
        val server = try {
            withContext(io) { ServerSocket(0) }   // ephemeral port, advertised below
        } catch (_: Exception) {
            failLocked()
            return
        }
        serverSocket = server
        discovery.advertise(displayName, server.localPort)

        val gen = generation
        acceptJob = scope.launch {
            val socket = try {
                runInterruptible(io) { server.accept() }
            } catch (_: Exception) {
                return@launch
            }
            lock.withLock {
                // The host takes the first guest and stops advertising — this is a two-player game.
                if (generation != gen || connection != null) {
                    runCatching { socket.close() }
                    return@withLock
                }
                // Close the listener but don't cancel `acceptJob`: we are running inside it, and
                // cancelling ourselves here would abandon the connection we just accepted.
                serverSocket?.let { runCatching { it.close() } }
                serverSocket = null
                discovery.stopAdvertising()
                bindLocked(socket)
            }
        }
    }

    private fun closeListenerLocked() {
        acceptJob?.cancel()
        acceptJob = null
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        discovery.stopAdvertising()
    }

    private fun startBrowserLocked() {
        browseJob?.cancel()
        discovery.startBrowsing()
        browseJob = scope.launch {
            discovery.peers.collect { peers ->
                // Collapse by name, like the Multipeer join list: a relaunched host can briefly
                // appear twice while its old advertisement times out.
                val seen = mutableSetOf<String>()
                _discoveredPeers.value = peers.filter { seen.add(it.name) }
            }
        }
    }

    private suspend fun connectTo(peer: LanPeer) {
        val address = discovery.resolve(peer)
        if (address == null) {
            lock.withLock { handleDropLocked() }
            return
        }
        val socket = try {
            withContext(io) {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(address.address, address.port), CONNECT_TIMEOUT_MS)
                }
            }
        } catch (_: Exception) {
            lock.withLock { handleDropLocked() }
            return
        }
        lock.withLock {
            if (connection != null) {
                runCatching { socket.close() }
                return@withLock
            }
            bindLocked(socket)
        }
    }

    // ---- Connection ----

    /** One live socket, stamped with the generation that created it. */
    private inner class Conn(val socket: Socket, val gen: Int) {
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
        var readerJob: Job? = null
        var writerJob: Job? = null

        fun close() {
            readerJob?.cancel()
            writerJob?.cancel()
            outgoing.close()
            runCatching { socket.close() }
        }
    }

    private fun bindLocked(socket: Socket) {
        runCatching { socket.tcpNoDelay = true }
        generation += 1
        val conn = Conn(socket, generation)
        connection = conn
        browseJob?.cancel(); browseJob = null
        discovery.stopBrowsing()

        conn.writerJob = scope.launch {
            val out = runCatching { socket.getOutputStream() }.getOrNull() ?: return@launch
            for (frame in conn.outgoing) {
                val sent = runCatching {
                    withContext(io) { out.write(frame); out.flush() }
                }
                if (sent.isFailure) {
                    lock.withLock { handleDropLocked(conn.gen) }
                    return@launch
                }
            }
        }

        conn.readerJob = scope.launch {
            val assembler = Ndjson.LineAssembler()
            val buf = ByteArray(64 * 1024)
            val input = runCatching { socket.getInputStream() }.getOrNull()
            if (input == null) {
                lock.withLock { handleDropLocked(conn.gen) }
                return@launch
            }
            while (isActive) {
                val read = try {
                    runInterruptible(io) { input.read(buf) }
                } catch (_: Exception) {
                    -1
                }
                if (read <= 0) break
                for (line in assembler.feed(buf, read)) {
                    Ndjson.decode(line)?.let { eventChannel.trySend(TransportEvent.Received(it)) }
                }
            }
            lock.withLock { handleDropLocked(conn.gen) }
        }

        markConnectedLocked()
    }

    // ---- Sending ----

    override suspend fun send(message: GameMessage) {
        val frame = Ndjson.frame(message)
        lock.withLock {
            val conn = connection
            if (conn == null || _phase.value != TransportPhase.CONNECTED) {
                bufferLocked(frame)
                return
            }
            conn.outgoing.trySend(frame)
        }
    }

    private fun bufferLocked(frame: ByteArray) {
        outbox.addLast(frame)
        trimOutboxLocked()
    }

    /** Keeps the *newest* messages: during a long gap the recent state is what matters. */
    private fun trimOutboxLocked() {
        while (outbox.size > OUTBOX_CAP) outbox.removeFirst()
    }

    private fun flushOutboxLocked() {
        val conn = connection ?: return
        while (outbox.isNotEmpty()) {
            conn.outgoing.trySend(outbox.removeFirst())
        }
    }

    // ---- Reconnect ----

    /**
     * The phase flips **synchronously**, before this returns, and only the rebuild is deferred.
     *
     * That ordering is load-bearing: a `send` racing in right behind a `reconnect` — a tap as the
     * app returns from the background, which is precisely when this gets called — must see
     * RECONNECTING and be buffered, rather than be written into a socket that is about to close.
     * iOS gets the same ordering for free from `@MainActor`.
     */
    override fun reconnect(force: Boolean) {
        if (!didConnect) return
        if (!force && _phase.value == TransportPhase.CONNECTED) return
        _phase.value = TransportPhase.RECONNECTING
        eventChannel.trySend(TransportEvent.Reconnecting)
        scope.launch { lock.withLock { rebuildLocked() } }
    }

    /**
     * Drop the current connection and start looking for the peer again.
     *
     * **Must not be called from a connection's own reader or writer coroutine.** Closing the
     * connection cancels those jobs, so the first suspension point after it — opening the new
     * `ServerSocket` — would throw `CancellationException` and the rebuild would silently never
     * happen. A host that dropped would then never come back. [handleDropLocked] hands off to a
     * fresh coroutine for exactly this reason.
     */
    private suspend fun rebuildLocked() {
        generation += 1              // orphan the old socket's reader and writer
        connection?.let { conn ->
            // Anything still queued on the dying socket goes back to the front of the outbox, in
            // order. Without this, a message handed over in the instant before a drop is lost —
            // and the whole point of the outbox is that a player's tap survives the gap.
            val stranded = mutableListOf<ByteArray>()
            while (true) {
                val result = conn.outgoing.tryReceive()
                stranded += result.getOrNull() ?: break
            }
            if (stranded.isNotEmpty()) {
                outbox.addAll(0, stranded)
                trimOutboxLocked()
            }
            conn.close()
        }
        connection = null
        if (isHost) {
            startListenerLocked()
        } else {
            startBrowserLocked()
            startRebrowseRetryLocked()
        }
    }

    /**
     * While reconnecting, a guest re-invites the first host it rediscovers. The host may not be
     * advertising again yet, so this keeps looking rather than giving up after one pass.
     */
    private fun startRebrowseRetryLocked() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) {
                delay(rebrowseIntervalMs)
                val peer = lock.withLock {
                    if (_phase.value != TransportPhase.RECONNECTING) return@launch
                    _discoveredPeers.value.firstOrNull()
                }
                if (peer != null) {
                    connectTo(peer)
                    return@launch
                }
                lock.withLock { startBrowserLocked() }
            }
        }
    }

    // ---- State transitions ----

    private fun markConnectedLocked() {
        _phase.value = TransportPhase.CONNECTED
        didConnect = true
        reconnectJob?.cancel(); reconnectJob = null
        _discoveredPeers.value = emptyList()
        flushOutboxLocked()
        eventChannel.trySend(TransportEvent.Connected)
    }

    /**
     * [gen] is the generation of the connection reporting the drop. A reader belonging to a socket
     * we have already replaced must not tear down the live one.
     */
    private fun handleDropLocked(gen: Int = generation) {
        if (gen != generation) return
        if (_phase.value == TransportPhase.RECONNECTING) return   // already recovering
        if (!didConnect) {
            failLocked()
            return
        }
        // Claim the recovery synchronously, so a simultaneous drop on the writer side sees
        // RECONNECTING and doesn't start a second one — then hand the actual rebuild to a fresh
        // coroutine, because this one is about to be cancelled by its own connection closing.
        _phase.value = TransportPhase.RECONNECTING
        eventChannel.trySend(TransportEvent.Reconnecting)
        scope.launch { lock.withLock { rebuildLocked() } }
    }

    private fun failLocked() {
        _phase.value = TransportPhase.DISCONNECTED
        eventChannel.trySend(TransportEvent.Disconnected)
    }

    private companion object {
        const val OUTBOX_CAP = 200
        const val CONNECT_TIMEOUT_MS = 8_000
    }
}
