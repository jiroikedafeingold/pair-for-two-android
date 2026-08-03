package com.jirofeingold.pairfortwo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jirofeingold.pairfortwo.core.GameViewModel
import com.jirofeingold.pairfortwo.core.net.LanTransport
import com.jirofeingold.pairfortwo.core.net.NearbyTransport
import com.jirofeingold.pairfortwo.feel.GameFeedback
import com.jirofeingold.pairfortwo.net.NsdLanDiscovery
import com.jirofeingold.pairfortwo.persistence.AndroidGamePersistence
import com.jirofeingold.pairfortwo.settings.AppSettings
import com.jirofeingold.pairfortwo.ui.theme.CribGold
import com.jirofeingold.pairfortwo.ui.theme.FeltDark
import com.jirofeingold.pairfortwo.ui.theme.FeltMid
import com.jirofeingold.pairfortwo.ui.theme.playerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class Screen { MENU, CONNECT, GAME }

/** Why the welcome tour is on screen: the first launch, or a replay from Help. */
private enum class Onboarding { FIRST_RUN, REPLAY }

/**
 * The app's top level — port of the iOS `RootView`: menu, connect, game.
 *
 * **Two-device only, as on iOS.** `GameViewModel.loopback` still exists and a whole pass-and-play
 * game runs in the JVM tests, but there is no menu entry for it: the Swift's own comment is "Single
 * device pass-and-play was removed — this is a two-phone game", and the two apps should offer the
 * same thing. There is also no "Play online" — Android has no Game Center equivalent and v1 doesn't
 * add a relay (PLAN.md §0).
 *
 * A fresh [LanTransport] is built for each attempt, deliberately: `stop()` closes its event channel,
 * so a transport is a one-shot. On a successful connect the connect screen hands ownership of the
 * live one to the game, and it is stopped when the game ends.
 */
@Composable
fun RootScaffold(
    settings: AppSettings,
    onChangeSettings: (AppSettings) -> Unit,
    feedback: GameFeedback,
    persistence: AndroidGamePersistence,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.MENU) }
    var showingSettings by remember { mutableStateOf(false) }
    var showingHelp by remember { mutableStateOf(false) }
    // Read once, from the first real settings value — `MainActivity` waits for that before composing
    // this, so there is no frame in which the default `false` could flash the tour at a returning
    // player. `isReplay` is what separates the first run from Help's "replay the welcome tour".
    var onboarding by remember { mutableStateOf(if (settings.hasOnboarded) null else Onboarding.FIRST_RUN) }
    var vm by remember { mutableStateOf<GameViewModel?>(null) }
    var transport by remember { mutableStateOf<NearbyTransport?>(null) }
    var resumeRole by remember { mutableStateOf<ResumeRole?>(null) }
    var resume by remember { mutableStateOf<AndroidGamePersistence.Resume?>(null) }

    // Refreshed every time the menu comes back, since a finished or abandoned game changes it.
    LaunchedEffect(screen) {
        if (screen == Screen.MENU) resume = persistence.resume()
    }

    // The scoring mode belongs to the game, not the device: the view model broadcasts a change so a
    // networked opponent switches with you.
    LaunchedEffect(settings.scoringMode, vm) { vm?.setScoringMode(settings.scoringMode) }
    LaunchedEffect(settings.localName, settings.localColorID, vm) {
        vm?.updateLocalIdentity(settings.player, settings.localColorID)
    }
    LaunchedEffect(settings.soundEnabled, settings.hapticsEnabled) {
        feedback.soundEnabled = settings.soundEnabled
        feedback.hapticsEnabled = settings.hapticsEnabled
    }

    val leaveGame: () -> Unit = {
        vm?.dispose()
        transport?.stop()
        vm = null
        transport = null
        resumeRole = null
        screen = Screen.MENU
    }

    // Returning from the background: force a rebuild of the link, because the OS reports a dead
    // socket as live for tens of seconds and waiting that out looks like a hang. A save on the way
    // out covers being killed while backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm?.reconnect(force = true)
                Lifecycle.Event.ON_STOP -> vm?.persist()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = showingSettings) { showingSettings = false }
    BackHandler(enabled = showingHelp) { showingHelp = false }

    CompositionLocalProvider(LocalCardBackID provides settings.cardBackID) {
        // Modal layers, outermost first. Onboarding covers everything on a first run; Help and
        // Settings sit over whichever screen opened them and hand back to it.
        val tour = onboarding
        if (tour != null) {
            OnboardingScreen(
                settings = settings,
                onChange = onChangeSettings,
                isReplay = tour == Onboarding.REPLAY,
                onFinish = {
                    if (tour == Onboarding.FIRST_RUN) {
                        onChangeSettings(settings.copy(hasOnboarded = true))
                    }
                    onboarding = null
                },
                modifier = modifier.fillMaxSize(),
            )
            return@CompositionLocalProvider
        }

        if (showingHelp) {
            HelpScreen(
                onBack = { showingHelp = false },
                // The tour is only offered from the menu, as on iOS — mid-game it would cover the
                // table with a five-page walkthrough of how to reach the table.
                onReplayOnboarding = if (screen == Screen.MENU) {
                    { showingHelp = false; onboarding = Onboarding.REPLAY }
                } else {
                    null
                },
                modifier = modifier.fillMaxSize(),
            )
            return@CompositionLocalProvider
        }

        if (showingSettings) {
            SettingsScreen(
                settings = settings,
                onChange = onChangeSettings,
                onBack = { showingSettings = false },
                modifier = modifier.fillMaxSize(),
            )
            return@CompositionLocalProvider
        }

        when (screen) {
            Screen.MENU -> Menu(
                settings = settings,
                resume = resume,
                onOpenSettings = { showingSettings = true },
                onOpenHelp = { showingHelp = true },
                onPlayNearby = {
                    // A new game supersedes any saved one, exactly as iOS does — the new game writes
                    // its own marker as it plays.
                    persistence.clear()
                    resume = null
                    resumeRole = null
                    transport = newTransport(context, settings.player, scope)
                    screen = Screen.CONNECT
                },
                onRejoin = { marker ->
                    // The device holding the state re-hosts. Decided by the file rather than by the
                    // marker's recorded role, so a stale marker can't leave both sides hosting.
                    resumeRole = if (marker.hasState) ResumeRole.HOST else ResumeRole.GUEST
                    transport = newTransport(context, settings.player, scope)
                    screen = Screen.CONNECT
                },
                modifier = modifier.fillMaxSize(),
            )

            Screen.CONNECT -> {
                val active = transport
                if (active == null) {
                    screen = Screen.MENU
                } else {
                    BackHandler { active.stop(); transport = null; screen = Screen.MENU }
                    ConnectScreen(
                        transport = active,
                        localName = settings.player,
                        resumeRole = resumeRole,
                        onConnected = { connected ->
                            scope.launch {
                                vm = buildViewModel(
                                    transport = connected,
                                    resumeRole = resumeRole,
                                    settings = settings,
                                    persistence = persistence,
                                    scope = scope,
                                )
                                screen = Screen.GAME
                            }
                        },
                        onCancel = { transport = null; screen = Screen.MENU },
                        modifier = modifier.fillMaxSize(),
                    )
                }
            }

            Screen.GAME -> {
                val game = vm
                if (game == null) {
                    screen = Screen.MENU
                } else {
                    GameTableScreen(
                        vm = game,
                        modifier = modifier.fillMaxSize(),
                        feedback = feedback,
                        confirmRelease = settings.confirmRelease,
                        scoreTrackEnabled = settings.scoreTrackEnabled,
                        celebrationEffects = settings.celebrationEffects,
                        replayBeforeWin = settings.replayBeforeWin,
                        onOpenSettings = { showingSettings = true },
                        onOpenHelp = { showingHelp = true },
                        onExit = leaveGame,
                    )
                }
            }
        }
    }
}

/**
 * A transport per attempt, over `NsdManager` discovery.
 *
 * The display name is the Bonjour service name, which is what the other device lists — so it is the
 * trimmed, never-blank [AppSettings.player] rather than the raw setting.
 */
private fun newTransport(
    context: android.content.Context,
    displayName: String,
    scope: CoroutineScope,
): NearbyTransport = LanTransport(
    displayName = displayName,
    discovery = NsdLanDiscovery(context.applicationContext, scope),
    scope = scope,
)

/**
 * Build the game for a freshly connected transport.
 *
 * A resuming host reloads its saved state and re-hosts it; everyone else starts (or rejoins) a
 * normal networked game and is resynced by whoever does hold the state.
 */
private suspend fun buildViewModel(
    transport: NearbyTransport,
    resumeRole: ResumeRole?,
    settings: AppSettings,
    persistence: AndroidGamePersistence,
    scope: CoroutineScope,
): GameViewModel {
    if (resumeRole == ResumeRole.HOST) {
        val saved = persistence.loadState()
        if (saved != null) {
            transport.isHost = true
            return GameViewModel.resumeHost(
                transport = transport,
                savedState = saved,
                scope = scope,
                persistence = persistence,
            )
        }
        // The marker said we host but the file has gone. Rather than deadlock — both sides waiting
        // to be resynced — fall through and start a fresh game from this side.
    }
    if (resumeRole == ResumeRole.GUEST) transport.isHost = false

    return GameViewModel.networked(
        transport = transport,
        localName = settings.player,
        localColorID = settings.localColorID,
        scope = scope,
        scoringMode = settings.scoringMode,
        persistence = persistence,
    )
}

// ---- Menu ----

@Composable
private fun Menu(
    settings: AppSettings,
    resume: AndroidGamePersistence.Resume?,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onPlayNearby: () -> Unit,
    onRejoin: (AndroidGamePersistence.Resume) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.background(Brush.verticalGradient(listOf(FeltMid, FeltDark))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = "How to play",
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 8.dp, end = 14.dp)
                .size(26.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenHelp,
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("Pair for Two", color = Color.White, style = tightTextStyle(30.sp, FontWeight.Black))
            Text(
                "Two-device cribbage",
                color = CribGold,
                style = tightTextStyle(14.sp, FontWeight.Medium),
            )

            // Your identity, tapped to edit in Settings — the same shortcut iOS puts here.
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.10f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenSettings,
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(playerTheme(settings.localColorID).primary, CircleShape),
                )
                Text(
                    settings.player,
                    color = Color.White,
                    style = tightTextStyle(15.sp, FontWeight.SemiBold),
                )
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit your name and colour",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }

            if (resume != null) {
                MenuButton(
                    label = "Rejoin game",
                    detail = resume.summary.takeIf { it.isNotBlank() },
                    icon = Icons.Filled.Refresh,
                    background = CribGold,
                    foreground = Color.Black,
                    onClick = { onRejoin(resume) },
                )
            }

            MenuButton(
                label = if (resume == null) "Play nearby" else "New nearby game",
                icon = Icons.Filled.Wifi,
                background = if (resume == null) CribGold else Color.White.copy(alpha = 0.22f),
                foreground = if (resume == null) Color.Black else Color.White,
                onClick = onPlayNearby,
            )
        }
    }
}

@Composable
private fun MenuButton(
    label: String,
    icon: ImageVector,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
    detail: String? = null,
) {
    Column(
        Modifier
            .background(background, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 26.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
            Text(label, color = foreground, style = tightTextStyle(17.sp, FontWeight.Bold))
        }
        if (detail != null) {
            Text(
                detail,
                color = foreground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                style = tightTextStyle(11.sp),
            )
        }
    }
}
