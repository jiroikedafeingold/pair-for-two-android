package com.jirofeingold.pairfortwo.core.harness

import com.jirofeingold.pairfortwo.core.GameMessage
import com.jirofeingold.pairfortwo.core.PairWireJson
import com.jirofeingold.pairfortwo.core.net.LanTransport
import com.jirofeingold.pairfortwo.core.net.TransportEvent
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * LAN interop harness — the Kotlin end. Pairs with `tools/lan-harness/main.swift` in the iOS repo.
 *
 * Runs the *real* [LanTransport] as a command-line process against the *real* iOS `LANTransport`,
 * over actual Bonjour and an actual TCP socket. Everything below the UI is production code; only
 * discovery is swapped for [DnsSdDiscovery], because a desktop JVM has no mDNS of its own.
 *
 * This is what proves the two apps can talk. The fixtures prove they agree on *bytes*; this proves
 * they can find each other, connect, and keep a stream of those bytes intact in both directions.
 *
 * ```
 *   --role host|guest   advertise and wait, or browse and join
 *   --mode drive|echo   send the corpus and check the echoes, or echo what arrives
 * ```
 *
 * Run through `tools/run-lan-interop.sh` in the iOS repo, which builds and pairs both ends.
 */
fun main(argv: Array<String>) {
    val args = argv.toList()
    fun arg(name: String, default: String? = null): String {
        val i = args.indexOf("--$name")
        if (i >= 0 && i + 1 < args.size) return args[i + 1]
        return default ?: fail("missing --$name")
    }

    val role = arg("role")
    val mode = arg("mode")
    val name = arg("name", "Kotlin")
    val peerName = arg("peer", "")
    val fixturesDir = arg("fixtures", "../fixtures/protocol-v1")
    val timeoutMs = arg("timeout", "60").toLong() * 1000

    val tag = "[kotlin/$role/$mode]"
    fun note(message: String) = println("$tag $message").also { System.out.flush() }

    /** Ordered by filename so both harnesses walk the corpus identically. */
    val corpus: List<Pair<String, GameMessage>> = File(fixturesDir)
        .listFiles { f: File -> f.extension == "json" }
        ?.sortedBy { it.name }
        ?.map { file ->
            file.nameWithoutExtension to
                PairWireJson.decodeFromString(GameMessage.serializer(), file.readText())
        }
        ?: fail("no fixtures at ${File(fixturesDir).absolutePath}")

    /**
     * Compares by canonical JSON tree rather than by `==`: what has to survive the wire is the
     * encoding, and two messages that encode identically are the same message for interop.
     */
    fun same(a: GameMessage, b: GameMessage): Boolean {
        val ja: JsonElement = PairWireJson.encodeToJsonElement(a)
        val jb: JsonElement = PairWireJson.encodeToJsonElement(b)
        return ja == jb
    }

    runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val discovery = DnsSdDiscovery(scope, excludeName = name)
        val transport = LanTransport(
            displayName = name,
            discovery = discovery,
            scope = scope,
            rebrowseIntervalMs = 500,
        )

        val inbox = mutableListOf<GameMessage>()
        var connected = false

        scope.launch {
            transport.events.collect { event ->
                when (event) {
                    is TransportEvent.Connected -> { connected = true; note("connected") }
                    is TransportEvent.Reconnecting -> note("reconnecting")
                    is TransportEvent.Disconnected -> note("disconnected")
                    is TransportEvent.Received -> {
                        synchronized(inbox) { inbox += event.message }
                        if (mode == "echo") transport.send(event.message)
                    }
                }
            }
        }

        suspend fun inboxSize() = synchronized(inbox) { inbox.size }
        suspend fun inboxAt(i: Int) = synchronized(inbox) { inbox[i] }

        suspend fun awaitInbox(n: Int, what: String) {
            val ok = withTimeoutOrNull(timeoutMs) {
                while (inboxSize() < n) delay(25)
                true
            }
            if (ok == null) fail("$tag timed out waiting for $what (${inboxSize()}/$n)")
        }

        if (role == "host") {
            transport.startHosting()
            note("advertising as '$name'")
        } else {
            transport.startBrowsing()
            note("browsing for '$peerName'")
            val peer = withTimeoutOrNull(timeoutMs) {
                var found = transport.discoveredPeers.value.firstOrNull { peerName.isEmpty() || it.name == peerName }
                while (found == null) {
                    delay(200)
                    found = transport.discoveredPeers.value.firstOrNull { peerName.isEmpty() || it.name == peerName }
                }
                found
            } ?: fail("$tag never discovered '$peerName'")
            note("found '${peer.name}' — inviting")
            transport.invite(peer)
        }

        withTimeoutOrNull(timeoutMs) { while (!connected) delay(50) } ?: fail("$tag never connected")

        if (mode == "drive") {
            note("corpus: ${corpus.size} messages")

            // Pass 1 — one at a time, so a mismatch names the exact message type.
            for ((fixtureName, message) in corpus) {
                val before = inboxSize()
                transport.send(message)
                awaitInbox(before + 1, "echo of $fixtureName")
                if (!same(inboxAt(before), message)) fail("$tag echo of '$fixtureName' differed")
            }
            note("✓ round-trip: ${corpus.size} message types, one at a time")

            // Pass 2 — back to back with no pauses, so the peer's reader has to reassemble whatever
            // TCP hands it. This is the pass that catches framing bugs.
            val before = inboxSize()
            for ((_, message) in corpus) transport.send(message)
            awaitInbox(before + corpus.size, "burst echoes")
            corpus.forEachIndexed { i, (fixtureName, message) ->
                if (!same(inboxAt(before + i), message)) {
                    fail("$tag burst echo $i ('$fixtureName') differed or arrived out of order")
                }
            }
            note("✓ burst: ${corpus.size} messages back to back, in order")
            note("PASS")
        } else {
            awaitInbox(corpus.size * 2, "the driver's two passes")
            note("✓ echoed ${inboxSize()} messages")
            note("PASS")
            // Let the last echo reach the driver before the socket dies with this process.
            delay(500)
        }

        transport.stop()
        discovery.shutdown()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
    exitProcess(0)
}

private fun fail(message: String): Nothing {
    System.err.println("FAIL: $message")
    exitProcess(1)
}
