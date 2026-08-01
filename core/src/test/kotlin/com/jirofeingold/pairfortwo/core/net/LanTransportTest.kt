package com.jirofeingold.pairfortwo.core.net

import com.jirofeingold.pairfortwo.core.Card
import com.jirofeingold.pairfortwo.core.GameMessage
import com.jirofeingold.pairfortwo.core.PlayerID
import com.jirofeingold.pairfortwo.core.Rank
import com.jirofeingold.pairfortwo.core.Suit
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * [LanTransport] driven over **real loopback sockets** — actual `ServerSocket`/`Socket`, actual
 * TCP, actual NDJSON on the wire. Only discovery is faked, because mDNS is the one piece that
 * genuinely can't run on a desktop JVM.
 *
 * That split is the reason the socket state machine lives in `:core` rather than `:app`: the
 * behaviours worth testing here — outbox buffering, reconnect after a drop, ghost-peer collapsing —
 * are exactly the ones that would otherwise need an emulator and two devices to exercise.
 */
@Timeout(30)
class LanTransportTest {

    // ---- Fake discovery ----

    private data class FakeHost(val id: String, val name: String, val port: Int)

    /** A stand-in for the local network's mDNS view, shared by both ends of a test. */
    private class FakeNetwork {
        val hosts = MutableStateFlow<List<FakeHost>>(emptyList())
    }

    private class FakeDiscovery(
        private val net: FakeNetwork,
        private val scope: CoroutineScope,
    ) : LanDiscovery {
        private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
        override val peers: StateFlow<List<LanPeer>> = _peers

        private var advertisedId: String? = null
        private var browseJob: Job? = null
        var advertiseCount = 0
            private set

        override fun advertise(displayName: String, port: Int) {
            advertiseCount += 1
            val id = "$displayName#$advertiseCount"
            advertisedId = id
            net.hosts.value = net.hosts.value + FakeHost(id, displayName, port)
        }

        override fun stopAdvertising() {
            val id = advertisedId ?: return
            net.hosts.value = net.hosts.value.filterNot { it.id == id }
            advertisedId = null
        }

        override fun startBrowsing() {
            browseJob?.cancel()
            browseJob = scope.launch {
                net.hosts.collect { hosts -> _peers.value = hosts.map { LanPeer(it.id, it.name) } }
            }
        }

        override fun stopBrowsing() {
            browseJob?.cancel()
            browseJob = null
        }

        override suspend fun resolve(peer: LanPeer): InetSocketAddress? {
            val host = net.hosts.value.firstOrNull { it.id == peer.id } ?: return null
            return InetSocketAddress(InetAddress.getLoopbackAddress(), host.port)
        }

        override fun shutdown() {
            stopBrowsing()
            stopAdvertising()
        }
    }

    // ---- Harness ----

    private val scopes = mutableListOf<CoroutineScope>()
    private val transports = mutableListOf<LanTransport>()

    private fun newScope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }

    /** A transport plus a drained event channel, so tests can await individual events. */
    private class End(val transport: LanTransport, val events: Channel<TransportEvent>)

    private fun transport(name: String, net: FakeNetwork): End {
        val scope = newScope()
        val t = LanTransport(
            displayName = name,
            discovery = FakeDiscovery(net, scope),
            scope = scope,
            rebrowseIntervalMs = 50,
        )
        transports += t
        val channel = Channel<TransportEvent>(Channel.UNLIMITED)
        scope.launch { t.events.collect { channel.send(it) } }
        return End(t, channel)
    }

    @AfterEach
    fun tearDown() {
        transports.forEach { it.stop() }
        scopes.forEach { it.cancel() }
    }

    private suspend fun Channel<TransportEvent>.awaitConnected() {
        withTimeout(10_000) { while (receive() !is TransportEvent.Connected) Unit }
    }

    private suspend fun Channel<TransportEvent>.awaitMessage(): GameMessage =
        withTimeout(10_000) {
            var event = receive()
            while (event !is TransportEvent.Received) event = receive()
            event.message
        }

    /** Runs the standard host/guest pairing and returns both ends, connected. */
    private suspend fun pair(net: FakeNetwork): Pair<End, End> {
        val host = transport("Host", net)
        val guest = transport("Guest", net)

        host.transport.startHosting()
        guest.transport.startBrowsing()

        val peer = withTimeout(10_000) { guest.transport.discoveredPeers.first { it.isNotEmpty() } }.single()
        assertEquals("Host", peer.name)
        guest.transport.invite(peer)

        host.events.awaitConnected()
        guest.events.awaitConnected()
        return host to guest
    }

    // ---- Tests ----

    @Test
    fun `host and guest exchange messages over a real socket`() = runBlocking {
        val (host, guest) = pair(FakeNetwork())

        assertTrue(host.transport.isHost)
        assertTrue(!guest.transport.isHost)
        assertEquals(TransportPhase.CONNECTED, host.transport.phase.value)
        assertEquals("Host", guest.transport.connectedPeerName.value)

        val hello = GameMessage.Hello(name = "Guest", colorID = 7, playerToken = "abc")
        guest.transport.send(hello)
        assertEquals(hello, host.events.awaitMessage())

        host.transport.send(GameMessage.AssignSeat(PlayerID.TWO))
        assertEquals(GameMessage.AssignSeat(PlayerID.TWO), guest.events.awaitMessage())

        // A message with nested structure, since that is what a real snapshot looks like.
        val play = GameMessage.IntentPlay(Card(Rank.SEVEN, Suit.CLUBS))
        guest.transport.send(play)
        assertEquals(play, host.events.awaitMessage())
    }

    @Test
    fun `a burst of messages arrives complete and in order`() = runBlocking {
        val (host, guest) = pair(FakeNetwork())

        // Enough to be split and coalesced arbitrarily by TCP — the framing has to survive it.
        val sent = (1..200).map { GameMessage.IntentCut(it) }
        sent.forEach { guest.transport.send(it) }

        val received = (1..200).map { host.events.awaitMessage() }
        assertEquals(sent, received)
    }

    @Test
    fun `messages sent before connecting are buffered and flushed on connect`() = runBlocking {
        val net = FakeNetwork()
        val host = transport("Host", net)
        val guest = transport("Guest", net)

        // The tap happens before there is anywhere to send it — this is the case that loses a
        // player's move if the outbox isn't there.
        guest.transport.send(GameMessage.IntentGo)
        guest.transport.send(GameMessage.Advance)

        host.transport.startHosting()
        guest.transport.startBrowsing()
        val peer = withTimeout(10_000) { guest.transport.discoveredPeers.first { it.isNotEmpty() } }.single()
        guest.transport.invite(peer)
        host.events.awaitConnected()
        guest.events.awaitConnected()

        assertEquals(GameMessage.IntentGo, host.events.awaitMessage())
        assertEquals(GameMessage.Advance, host.events.awaitMessage())
    }

    @Test
    fun `the outbox is capped, dropping the oldest`() = runBlocking {
        val net = FakeNetwork()
        val host = transport("Host", net)
        val guest = transport("Guest", net)

        // 250 sends with nowhere to go. The cap keeps the *newest* 200: during a long gap the
        // recent state is what matters, and an unbounded queue is how a transport runs a device
        // out of memory.
        repeat(250) { guest.transport.send(GameMessage.IntentCut(it)) }

        host.transport.startHosting()
        guest.transport.startBrowsing()
        val peer = withTimeout(10_000) { guest.transport.discoveredPeers.first { it.isNotEmpty() } }.single()
        guest.transport.invite(peer)
        host.events.awaitConnected()

        val received = (1..200).map { host.events.awaitMessage() }
        assertEquals(GameMessage.IntentCut(50), received.first(), "the oldest should have been dropped")
        assertEquals(GameMessage.IntentCut(249), received.last())
    }

    @Test
    fun `a dropped connection is re-established and messages flow again`() = runBlocking {
        val net = FakeNetwork()
        val (host, guest) = pair(net)

        // Force a rebuild the way returning from the background does: the host tears its socket
        // down, so the guest sees the far end vanish and has to find it again.
        host.transport.reconnect(force = true)

        host.events.awaitConnected()
        guest.events.awaitConnected()

        assertEquals(TransportPhase.CONNECTED, host.transport.phase.value)
        assertEquals(TransportPhase.CONNECTED, guest.transport.phase.value)

        val after = GameMessage.IntentPlay(Card(Rank.ACE, Suit.SPADES))
        guest.transport.send(after)
        assertEquals(after, host.events.awaitMessage())
    }

    @Test
    fun `a message sent during a reconnect is delivered afterwards`() = runBlocking {
        val net = FakeNetwork()
        val (host, guest) = pair(net)

        guest.transport.reconnect(force = true)
        // Sent while there is no socket at all — it must survive the gap.
        guest.transport.send(GameMessage.IntentGo)

        host.events.awaitConnected()
        guest.events.awaitConnected()
        assertEquals(GameMessage.IntentGo, host.events.awaitMessage())
    }

    @Test
    fun `a relaunched host appearing twice is collapsed to one row`() = runBlocking {
        val net = FakeNetwork()
        val guest = transport("Guest", net)
        guest.transport.startBrowsing()

        // The stale advertisement of a host that just relaunched, alongside its new one. Without
        // collapsing, the join list offers the same person twice and one of the rows is dead.
        net.hosts.value = listOf(
            FakeHost(id = "Host#1", name = "Host", port = 1111),
            FakeHost(id = "Host#2", name = "Host", port = 2222),
            FakeHost(id = "Other#1", name = "Other", port = 3333),
        )

        val peers = withTimeout(10_000) { guest.transport.discoveredPeers.first { it.size >= 2 } }
        assertEquals(listOf("Host", "Other"), peers.map { it.name })
        assertEquals("Host#1", peers.first().id, "the first advertisement seen should win")
    }

    @Test
    fun `failing to reach a host before ever connecting is terminal, not a retry loop`() = runBlocking {
        val net = FakeNetwork()
        val guest = transport("Guest", net)
        guest.transport.startBrowsing()

        // Advertised, but nothing is listening on that port — AP isolation looks like this, and
        // the connect screen has to be able to say so rather than spin forever.
        net.hosts.value = listOf(FakeHost(id = "Ghost#1", name = "Ghost", port = unusedPort()))
        val peer = withTimeout(10_000) { guest.transport.discoveredPeers.first { it.isNotEmpty() } }.single()
        guest.transport.invite(peer)

        withTimeout(15_000) {
            while (guest.events.receive() !is TransportEvent.Disconnected) Unit
        }
        assertEquals(TransportPhase.DISCONNECTED, guest.transport.phase.value)
    }

    /** A port nothing is listening on: bind one, note it, release it. */
    private fun unusedPort(): Int =
        java.net.ServerSocket(0).use { it.localPort }
}
