package com.jirofeingold.pairfortwo.core.net

import java.net.InetSocketAddress
import kotlinx.coroutines.flow.StateFlow

/** A host found while browsing. [id] is the mDNS service name, stable for a given advertiser. */
data class LanPeer(val id: String, val name: String)

/**
 * How hosts are advertised and found on the local network.
 *
 * Split out from [LanTransport] because it is the only genuinely platform-specific piece: sockets
 * are plain JVM and run unchanged on Android, but mDNS is `NsdManager` there, `NWBrowser` on iOS,
 * and neither exists on a desktop JVM. Keeping the seam here means the socket state machine — the
 * part with the hard-won reconnect behaviour — is testable on the JVM with no emulator, and the
 * same class can be driven from a command-line harness against a real iPhone.
 *
 * Implementations: `NsdLanDiscovery` in `:app`, `FixedHostDiscovery` for the harness, and a fake
 * in the tests.
 */
interface LanDiscovery {

    /**
     * The Bonjour/mDNS service type, shared with iOS.
     *
     * **Deliberately not `_pairfortwo._tcp`.** iOS's `MultipeerSession` uses
     * `serviceType = "pairfortwo"`, which makes MultipeerConnectivity register exactly that
     * Bonjour type — so sharing it would make this browser discover Multipeer advertisements, and
     * a guest would then open a raw TCP socket to a peer speaking MC's own protocol. A separate
     * type keeps the two transports' discovery disjoint. See `LANTransport.swift`.
     *
     * PLAN.md §4.2 and §4.3 still say `_pairfortwo._tcp`; they predate that discovery and are
     * stale on this point.
     */
    companion object {
        const val SERVICE_TYPE = "_pairfortwo-lan._tcp"
    }

    /** Hosts seen so far while browsing. Collapsed by name — see [LanTransport]. */
    val peers: StateFlow<List<LanPeer>>

    /** Advertise this device as a host reachable on [port]. */
    fun advertise(displayName: String, port: Int)

    fun stopAdvertising()

    fun startBrowsing()

    fun stopBrowsing()

    /**
     * Resolves a discovered peer to something connectable, or null if it can't be reached.
     *
     * Suspending because resolution is a network round-trip, and because `NsdManager`'s pre-API-34
     * implementation is historically racy enough that resolves have to be serialised and retried
     * (PLAN.md §11) — which is far easier to express here than in a callback.
     */
    suspend fun resolve(peer: LanPeer): InetSocketAddress?

    /** Release any OS resources — multicast locks, registered listeners. */
    fun shutdown()
}
