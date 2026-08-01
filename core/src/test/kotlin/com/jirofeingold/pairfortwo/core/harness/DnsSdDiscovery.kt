package com.jirofeingold.pairfortwo.core.harness

import com.jirofeingold.pairfortwo.core.net.LanDiscovery
import com.jirofeingold.pairfortwo.core.net.LanPeer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [LanDiscovery] for the **macOS interop harness only**, driving the system `dns-sd` tool.
 *
 * The desktop JVM has no mDNS, and pulling in a Bonjour library to run one dev harness would mean
 * adding a dependency to ship nothing. `dns-sd` is part of macOS, speaks the same mDNSResponder
 * that iOS's `NWBrowser` uses, and is entirely adequate for a tool whose whole job is to stand in
 * for a phone for a few seconds.
 *
 * Not shipped and not Android: the app uses `NsdLanDiscovery`. This lives in the test source set
 * deliberately, so it cannot be reached from production code.
 */
class DnsSdDiscovery(
    private val scope: CoroutineScope,
    private val excludeName: String? = null,
) : LanDiscovery {

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    override val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private var registerProcess: Process? = null
    private var browseProcess: Process? = null
    private var browseJob: Job? = null

    override fun advertise(displayName: String, port: Int) {
        stopAdvertising()
        // `dns-sd -R` advertises for as long as it runs, so the process *is* the advertisement.
        registerProcess = ProcessBuilder(
            "dns-sd", "-R", displayName, LanDiscovery.SERVICE_TYPE, "local", port.toString(),
        ).redirectErrorStream(true).start()
    }

    override fun stopAdvertising() {
        registerProcess?.destroyForcibly()
        registerProcess = null
    }

    override fun startBrowsing() {
        if (browseProcess != null) return
        val process = ProcessBuilder("dns-sd", "-B", LanDiscovery.SERVICE_TYPE, "local")
            .redirectErrorStream(true)
            .start()
        browseProcess = process
        browseJob = scope.launch(Dispatchers.IO) {
            // Lines look like:
            //   Timestamp  A/R Flags if Domain  Service Type          Instance Name
            //   16:04:05.123  Add  3  4 local.  _pairfortwo-lan._tcp. Jiro
            process.inputStream.bufferedReader().forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 7) return@forEachLine
                val action = parts[1]
                if (action != "Add" && action != "Rmv") return@forEachLine
                // The instance name can contain spaces, so it is everything after the type.
                val name = parts.drop(6).joinToString(" ")
                if (name.isEmpty() || name == excludeName) return@forEachLine
                _peers.value = when (action) {
                    "Add" -> if (_peers.value.any { it.id == name }) _peers.value
                    else _peers.value + LanPeer(id = name, name = name)
                    else -> _peers.value.filterNot { it.id == name }
                }
            }
        }
    }

    override fun stopBrowsing() {
        browseJob?.cancel()
        browseJob = null
        browseProcess?.destroyForcibly()
        browseProcess = null
    }

    /**
     * `dns-sd -L` prints, among other things:
     *   `Jiro._pairfortwo-lan._tcp.local. can be reached at MacBook.local.:52913 (interface 4)`
     */
    override suspend fun resolve(peer: LanPeer): InetSocketAddress? = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("dns-sd", "-L", peer.name, LanDiscovery.SERVICE_TYPE, "local")
            .redirectErrorStream(true)
            .start()
        try {
            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                val match = REACHABLE.find(line) ?: continue
                val host = match.groupValues[1].trimEnd('.')
                val port = match.groupValues[2].toIntOrNull() ?: continue
                val address = runCatching { InetAddress.getByName(host) }.getOrNull()
                    ?: InetAddress.getLoopbackAddress()
                return@withContext InetSocketAddress(address, port)
            }
            null
        } finally {
            process.destroyForcibly()
        }
    }

    override fun shutdown() {
        stopBrowsing()
        stopAdvertising()
    }

    private companion object {
        val REACHABLE = Regex("""can be reached at\s+(\S+):(\d+)""")
        const val RESOLVE_TIMEOUT_MS = 10_000L
    }
}
