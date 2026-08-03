package com.jirofeingold.pairfortwo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jirofeingold.pairfortwo.core.net.LanPeer
import com.jirofeingold.pairfortwo.core.net.NearbyTransport
import com.jirofeingold.pairfortwo.core.net.TransportPhase
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.FeltMid
import com.jirofeingold.pairfortwo.ui.theme.PairForTwoTheme
import kotlinx.coroutines.delay

/** Which side is rejoining a saved game. Port of the iOS `ResumeRole`. */
enum class ResumeRole { HOST, GUEST }

/**
 * Host or join a nearby game — the Android counterpart of the iOS `ConnectView`.
 *
 * **Simpler than iOS's, by one whole transport.** iOS runs Multipeer and LAN side by side and merges
 * their discovery, because Multipeer reaches another iPhone with no network at all. Android has only
 * [com.jirofeingold.pairfortwo.core.net.LanTransport], so there is one state machine to render and
 * no protocol for the player to be aware of. An iPhone advertising over Bonjour appears in this list
 * like any other device.
 *
 * **Resuming skips the choice.** The device holding the saved game hosts, the other joins and gets
 * resynced — [ResumeRole], decided by who actually holds the state rather than by a marker that can
 * go stale. That is iOS's own rule (`RootView.onConnected`), applied a moment earlier because the LAN
 * transport has no rendezvous mode in which both sides advertise *and* browse.
 *
 * @param transport driven directly, and handed to the caller on success — so [onConnected] must take
 *   ownership of it. Cancelling stops it here.
 */
@Composable
fun ConnectScreen(
    transport: NearbyTransport,
    localName: String,
    onConnected: (NearbyTransport) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    resumeRole: ResumeRole? = null,
) {
    val phase by transport.phase.collectAsStateWithLifecycle()
    val peers by transport.discoveredPeers.collectAsStateWithLifecycle()
    val peerName by transport.connectedPeerName.collectAsStateWithLifecycle()
    val resuming = resumeRole != null

    // Both sides can report connected within a frame of each other; only hand up once.
    var handedOff by remember { mutableStateOf(false) }
    // Surfaced after a while so a rejoin that can't find the other phone isn't a silent spinner.
    var stalled by remember { mutableStateOf(false) }

    // A resume needs no choice: the roles were decided before this screen opened.
    LaunchedEffect(resumeRole) {
        when (resumeRole) {
            ResumeRole.HOST -> transport.startHosting()
            ResumeRole.GUEST -> transport.startBrowsing()
            null -> Unit
        }
    }

    // A rejoining guest connects to the first host it finds — there is only ever meant to be one,
    // and asking a player to pick their own game back out of a list would be a strange thing to do.
    LaunchedEffect(resumeRole, peers) {
        if (resumeRole == ResumeRole.GUEST && phase == TransportPhase.BROWSING) {
            peers.firstOrNull()?.let { transport.invite(it) }
        }
    }

    LaunchedEffect(phase) {
        if (phase == TransportPhase.CONNECTED && !handedOff) {
            handedOff = true
            onConnected(transport)
        }
    }

    LaunchedEffect(resuming) {
        if (!resuming) return@LaunchedEffect
        delay(STALL_AFTER_MS)
        stalled = true
    }

    val cancel: () -> Unit = {
        transport.stop()
        onCancel()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(FeltMid, FeltDark))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            Text(
                if (resuming) "Resume Game" else "Play Nearby",
                color = Color.White,
                style = tightTextStyle(30.sp, FontWeight.Black),
            )
            Text(
                "Same Wi-Fi · no internet needed",
                color = CribGold,
                style = tightTextStyle(14.sp, FontWeight.Medium),
            )

            when {
                phase == TransportPhase.CONNECTED ->
                    Status(spinner = false, icon = Icons.Filled.Check, title = "Connected to ${peerName ?: "player"}!")

                phase == TransportPhase.CONNECTING || phase == TransportPhase.RECONNECTING -> {
                    Status(
                        title = if (resuming) "Reconnecting your game…" else "Connecting…",
                        detail = if (resuming) "Make sure the other device also tapped Rejoin game." else null,
                    )
                    if (stalled) StalledNote(onBack = cancel)
                }

                phase == TransportPhase.HOSTING -> Status(
                    title = if (resuming) "Waiting for the other player to rejoin…"
                    else "Waiting for a player to join…",
                    detail = "Have them tap ${if (resuming) "Rejoin game" else "Join a game"} on their device.",
                )

                phase == TransportPhase.BROWSING && resumeRole == ResumeRole.GUEST -> {
                    Status(
                        title = "Rejoining your game…",
                        detail = "Make sure the other device tapped Rejoin game.",
                    )
                    if (stalled) StalledNote(onBack = cancel)
                }

                phase == TransportPhase.BROWSING -> Browsing(peers) { transport.invite(it) }

                phase == TransportPhase.DISCONNECTED -> Unreachable { transport.startBrowsing() }

                else -> Idle(
                    localName = localName,
                    onHost = { transport.startHosting() },
                    onJoin = { transport.startBrowsing() },
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = cancel,
                )
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text("Back", color = Color.White, style = tightTextStyle(15.sp, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun Idle(localName: String, onHost: () -> Unit, onJoin: () -> Unit) {
    Text(
        "Playing as $localName",
        color = Color.White.copy(alpha = 0.85f),
        style = tightTextStyle(15.sp, FontWeight.Medium),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        BigButton("Host a game", Icons.Filled.Wifi, onHost)
        BigButton("Join a game", Icons.Filled.Search, onJoin)
    }
    Hint("Both devices must be on the same Wi-Fi network — a personal hotspot counts.")
}

@Composable
private fun Browsing(peers: List<LanPeer>, onInvite: (LanPeer) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
        Text("Looking for nearby games…", color = Color.White, style = tightTextStyle(16.sp))
    }

    if (peers.isEmpty()) {
        Hint("No hosts yet — make sure the other device tapped Host a game, and that you're both on the same Wi-Fi.")
        return
    }

    Column(
        Modifier.widthIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (peer in peers) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onInvite(peer) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    peer.name,
                    color = Color.White,
                    style = tightTextStyle(16.sp, FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = CribGold,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The terminal state, and the one that earns its keep on Android.
 *
 * Plenty of public and guest networks enable AP isolation, which silently drops device-to-device
 * traffic — discovery works, the socket never connects, and without saying so this screen would be a
 * spinner forever (PLAN.md §11).
 */
@Composable
private fun Unreachable(onRetry: () -> Unit) {
    Text(
        "Couldn't reach the other device",
        color = Color.White,
        style = tightTextStyle(18.sp, FontWeight.Bold),
    )
    Hint(
        "Check both devices are on the same Wi-Fi network. Some public and guest networks block " +
            "devices from talking to each other — if that's the case, try a personal hotspot.",
    )
    GoldPill("Try again", onRetry)
}

@Composable
private fun StalledNote(onBack: () -> Unit) {
    Hint(
        "Still can't find the other device. If it keeps failing, both players can go back and " +
            "start a new game.",
        color = CribGold,
    )
    GoldPill("Back to menu", onBack)
}

@Composable
private fun Status(
    title: String,
    detail: String? = null,
    spinner: Boolean = true,
    icon: ImageVector? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (spinner) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp),
            )
        } else if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color(0xFF4CD964),
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            title,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = tightTextStyle(16.sp, FontWeight.Medium),
        )
        if (detail != null) Hint(detail)
    }
}

@Composable
private fun Hint(text: String, color: Color = Color.White.copy(alpha = 0.6f)) {
    Text(
        text,
        color = color,
        textAlign = TextAlign.Center,
        style = tightTextStyle(13.sp),
        modifier = Modifier.widthIn(max = 420.dp),
    )
}

@Composable
private fun BigButton(title: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 150.dp, height = 104.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .border(1.dp, CribGold.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(title, color = Color.White, style = tightTextStyle(15.sp, FontWeight.SemiBold))
    }
}

@Composable
private fun GoldPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.Black,
        style = tightTextStyle(15.sp, FontWeight.Bold),
        modifier = Modifier
            .background(CribGold, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}

private const val STALL_AFTER_MS = 15_000L

@Preview(showBackground = true, widthDp = 900, heightDp = 420)
@Composable
private fun ConnectScreenPreview() {
    PairForTwoTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(FeltMid, FeltDark))),
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                Text("Play Nearby", color = Color.White, style = tightTextStyle(30.sp, FontWeight.Black))
                Text("Same Wi-Fi · no internet needed", color = CribGold, style = tightTextStyle(14.sp))
                Idle(localName = "Jiro", onHost = {}, onJoin = {})
            }
        }
    }
}
