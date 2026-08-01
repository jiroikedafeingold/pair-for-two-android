package com.jirofeingold.pairfortwo.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.jirofeingold.pairfortwo.core.net.LanDiscovery
import com.jirofeingold.pairfortwo.core.net.LanPeer
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LanDiscovery] over Android's `NsdManager` — the mDNS half of cross-platform local play.
 *
 * The iOS side advertises the same service type through `NWListener`, and the Bonjour service name
 * *is* the player's display name on both platforms, so a join list needs no extra handshake to
 * label its rows.
 *
 * ## Working around NsdManager
 *
 * `NsdManager`'s pre-API-34 implementation is famously unreliable, and two of its failure modes
 * would otherwise look like "Android can't see the iPhone":
 *
 * - **Concurrent resolves fail.** A second `resolveService` while one is in flight returns
 *   `FAILURE_ALREADY_ACTIVE` for *both*. Resolves are serialised through [resolveMutex] and retried.
 * - **mDNS needs a multicast lock.** Many devices drop multicast traffic to save power unless one
 *   is held, and discovery then silently finds nothing at all. Held only while browsing.
 *
 * `resolveService` is deprecated from API 34 in favour of `registerServiceInfoCallback`, but it
 * still works and the callback API needs a different shape for a one-shot resolve. Worth revisiting
 * when minSdk rises; the seam is entirely inside [resolve].
 */
class NsdLanDiscovery(
    context: Context,
    private val scope: CoroutineScope,
) : LanDiscovery {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val multicastLock by lazy {
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply { setReferenceCounted(true) }
    }

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    override val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val resolveMutex = Mutex()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * The name the system actually registered. NsdManager renames on conflict — a second "Jiro" on
     * the network becomes "Jiro (2)" — so this has to come from the callback, not from what we
     * asked for, or we would fail to filter out our own advertisement.
     */
    @Volatile
    private var registeredName: String? = null

    // ---- Advertising ----

    override fun advertise(displayName: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = LanDiscovery.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredName = info.serviceName
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registeredName = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredName = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    override fun stopAdvertising() {
        val listener = registrationListener ?: return
        registrationListener = null
        registeredName = null
        // Throws if registration never completed — that is a normal race on a fast stop.
        runCatching { nsdManager.unregisterService(listener) }
    }

    // ---- Browsing ----

    override fun startBrowsing() {
        if (discoveryListener != null) return
        runCatching { multicastLock.acquire() }
        _peers.value = emptyList()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.matchesOurType()) return
                if (info.serviceName == registeredName) return   // our own advertisement
                addPeer(LanPeer(id = info.serviceName, name = info.serviceName))
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                _peers.value = _peers.value.filterNot { it.id == info.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopBrowsing()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(LanDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { stopBrowsing() }
    }

    override fun stopBrowsing() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
        _peers.value = emptyList()
    }

    private fun addPeer(peer: LanPeer) {
        if (_peers.value.any { it.id == peer.id }) return
        _peers.value = _peers.value + peer
    }

    /** NsdManager reports the type with a trailing dot on some versions, and not on others. */
    private fun String.matchesOurType(): Boolean =
        trimEnd('.').equals(LanDiscovery.SERVICE_TYPE.trimEnd('.'), ignoreCase = true)

    // ---- Resolving ----

    override suspend fun resolve(peer: LanPeer): InetSocketAddress? = resolveMutex.withLock {
        repeat(RESOLVE_ATTEMPTS) { attempt ->
            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolveOnce(peer) }
            if (resolved != null) return@withLock resolved
            // FAILURE_ALREADY_ACTIVE from another app's resolve, or a dropped mDNS packet. Both
            // clear on their own; backing off is more effective than failing the join.
            if (attempt < RESOLVE_ATTEMPTS - 1) delay(RESOLVE_BACKOFF_MS)
        }
        null
    }

    private suspend fun resolveOnce(peer: LanPeer): InetSocketAddress? =
        suspendCancellableCoroutine { cont ->
            val info = NsdServiceInfo().apply {
                serviceName = peer.id
                serviceType = LanDiscovery.SERVICE_TYPE
            }

            @Suppress("DEPRECATION")
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host
                    if (!cont.isActive) return
                    cont.resume(if (host == null) null else InetSocketAddress(host, info.port))
                }
            }
            @Suppress("DEPRECATION")
            runCatching { nsdManager.resolveService(info, listener) }
                .onFailure { if (cont.isActive) cont.resume(null) }
        }

    // ---- Teardown ----

    override fun shutdown() {
        stopBrowsing()
        stopAdvertising()
    }

    private companion object {
        const val MULTICAST_LOCK_TAG = "pairfortwo-lan"
        const val RESOLVE_ATTEMPTS = 3
        const val RESOLVE_TIMEOUT_MS = 5_000L
        const val RESOLVE_BACKOFF_MS = 400L
    }
}
