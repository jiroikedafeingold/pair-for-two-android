# Pair for two — Android port plan

Android version of the iOS cribbage app at `~/Projects/Pair for two`
(~7,500 lines of Swift, 32 files). The two apps must be able to play each other
**locally**, look and feel like siblings, and each be idiomatic on its own platform.

Conventions follow the existing `~/Projects/StarBattleAndroid` port, which this plan
treats as the house style for Kotlin/Compose work.

---

## Decisions taken up front

| Question | Decision |
| --- | --- |
| Cross-platform local link | **mDNS + TCP over Wi-Fi.** Both devices on the same network (a personal hotspot counts). |
| iOS↔iOS nearby play | **Unchanged.** MultipeerConnectivity stays; the LAN transport is added alongside. |
| Online play on Android | **Not in v1.** Android is local-play only; iOS keeps Game Center for iOS↔iOS. |
| Sound | **Pre-render the iOS synthesis to WAVs**, ship in `res/raw`, play via `SoundPool`. |

### Why there is no "Android Game Center"

Google **shut down** the Play Games real-time and turn-based multiplayer APIs on
31 March 2020 and never replaced them; the official guidance is to build your own on
Firebase or Cloud Open Match. Play Games Services today is sign-in, achievements,
leaderboards, and saved games only. Since the iOS app uses Game Center purely for
matchmaking (no leaderboards or achievements), PGS would add a login screen and nothing
else. **We are not integrating PGS.** Cross-platform *online* play, if ever wanted, is a
separate project: a relay server plus room codes, added to both apps behind the existing
transport interface.

---

## 0. The three findings that shape this plan

**0.1 — Multipeer is a dead end for Android.** MultipeerConnectivity is Apple-proprietary.
Cross-platform local play requires a new transport **in both apps**, not just in Android.
This is the single largest piece of iOS work in the project.

**0.2 — The current wire format is Swift-specific and would silently break interop.**
Verified by running the encoder:

```
[PlayerID: Int]   →  {"scores":["two",5,"one",3]}          // flat array, NOT an object
                                                            // …and in nondeterministic order
enum + values     →  {"hello":{"colorID":2,"name":"Jiro"}}  // externally tagged
empty case        →  {"intentGo":{}}
UUID              →  "6BA7B810-9DAD-11D1-80B4-00C04FD430C8" // uppercase
```

Swift encodes enum-keyed dictionaries as a flat alternating `[k,v,k,v]` array because
`PlayerID` doesn't conform to `CodingKeyRepresentable`; kotlinx.serialization writes a JSON
object. `GameState` and `PlayerSnapshot` are full of `[PlayerID: …]` maps. Deriving the
Kotlin format from the Swift default is therefore not an option — we freeze an **explicit,
versioned protocol** owned by neither language's codegen. This must land before any
transport work.

**0.3 — Host authority massively de-risks the port.** The host is the sole referee: guests
send intents, the host mutates the canonical `GameState` and broadcasts redacted
`PlayerSnapshot`s. Consequences worth stating plainly:

- The two platforms **never need bit-identical RNG.** Only the host shuffles, and it ships
  the resulting state. Swift's `shuffle(using:)` consumption of `SeededGenerator` is a
  stdlib implementation detail we do **not** have to replicate.
- The two platforms **never need to score the same hand simultaneously.** But they must
  each score *correctly*, or a game would play differently depending on who hosts — hence
  the golden-fixture tests in §5.

---

## 1. Project setup

**Current state:** an empty Android Studio scaffold — package `com.example.pairfortwo`, one
`MainActivity.kt` and a default theme. Nothing to preserve.

- **Repo location: `~/Projects/PairForTwoAndroid`** — done. Moved from
  `~/Pair for two` to match `StarBattleAndroid` and to stop the folder name colliding with
  the iOS project at `~/Projects/Pair for two`. `rootProject.name` is now
  `PairForTwoAndroid`; the user-visible `app_name` string stays **"Pair for two"**. The
  stale `.gradle` cache and `.idea` scratch state were dropped in the move.
- Package/applicationId → `com.jirofeingold.pairfortwo`.
- Modules, mirroring StarBattleAndroid:
  - **`:core`** — pure Kotlin/JVM. Models, `CribbageEngine`, `CribbageScorer`, `GameState`,
    wire protocol. No Android dependencies, so it tests on the JVM in milliseconds with no
    emulator. This is where most of the risk lives and where most of the tests go.
  - **`:app`** — Compose UI, transport, `feel/`, persistence, platform glue.
- `minSdk 26` (VibrationEffect amplitude control + adaptive icons, same reasoning as
  StarBattle), `targetSdk 36`, AGP 9, Compose BOM, kotlinx.serialization, DataStore.
- `keystore.properties` + gitignored `.jks`, release build tolerates a missing key —
  copy the StarBattleAndroid `app/build.gradle.kts` pattern verbatim.
- Git init, `.gitignore`, and a `RELEASE.md`.

---

## 2. Wire protocol v1 — do this first

The interop contract. Deliverable: **`PROTOCOL.md`, committed identically to both repos**,
plus a shared directory of golden JSON fixtures.

**Framing.** Newline-delimited JSON over a TCP stream. JSON never contains a raw newline,
so this is unambiguous, trivially debuggable with `nc`, and avoids a length-prefix
endianness argument.

**Envelope.** Internally tagged, short key, explicit:

```json
{"t":"hello","protocol":1,"name":"Jiro","colorID":2,"playerToken":"6ba7b810-…"}
{"t":"intentPlay","card":{"rank":1,"suit":"spades"}}
{"t":"intentGo"}
```

**Rules that resolve the §0.2 mismatches:**

| Type | Wire form |
| --- | --- |
| `[PlayerID: T]` | JSON object keyed by raw value: `{"one":0,"two":0}` |
| `Set<PlayerID>` | Array, **sorted** for determinism: `["one","two"]` |
| `UUID` | Lowercase canonical string (Swift emits uppercase — normalise on write) |
| `Card` | `{"rank":1,"suit":"spades"}` — already clean on both sides, no change |
| `GamePhase`, `Seat` | String raw values (already clean) |
| `ScoringMode` | Int raw value (already clean) |
| Absent optionals | Omit the key entirely; don't emit `null` |

**Version handshake.** `hello` carries `"protocol": 1`. A mismatch surfaces a clear
"update one of your apps" message rather than a mysterious decode failure. Cheap now,
invaluable the first time the two apps ship out of step.

**iOS work — additive, and narrower than it first appears.** Add a `WireCodec.swift` DTO
layer that maps to/from the internal types. Do **not** hand-write `encode(to:)` on the live
model types and do **not** remove their `Codable` conformance — a separate DTO layer keeps
the protocol frozen even if someone later renames a model property, which is exactly the
kind of accident that breaks a shipped client.

Verified against the source: there are only **seven** serialisation sites in the whole iOS
app, and they split cleanly.

| Type | Encoded where | Action |
| --- | --- | --- |
| `GameMessage` (wrapping `PlayerSnapshot`) | `MultipeerTransport` ×3, `GameCenterTransport` ×2 | **Gets a wire DTO.** |
| `GameState` | `Persistence.swift` ×2 — **file on disk only** | **Untouched.** |

`GameState` never crosses the wire; only the redacted `PlayerSnapshot` does. So the saved-game
format does not change, and an in-progress game survives the update. The wire work is
confined to `GameMessage` + `PlayerSnapshot`, behind the five transport call sites.

**Android work.** Matching `@Serializable` DTOs in `:core`, with
`JsonBuilder { encodeDefaults = false; explicitNulls = false }`.

**Tests.** Golden fixtures — one JSON file per message type and a few full snapshots. Swift
tests decode the Kotlin-generated corpus and vice versa. This is the highest-value test in
the project; everything else is recoverable, a protocol drift in the field is not.

### 2.1 Backward compatibility on iOS — dual format

Moving iOS to the explicit protocol also changes the **Multipeer** wire format, so a new
iOS build would not talk to an already-installed one. **Decision: ship both formats for a
transitional period.**

- **Reading** is permissive: sniff the payload and decode either the legacy
  derived-`Codable` shape or protocol v1. Cheap and unambiguous — legacy `GameMessage` is
  externally tagged (`{"hello":{…}}`), v1 is internally tagged (`{"t":"hello",…}`), so a
  `"t"` key at the root decides it in one check.
- **Writing follows the peer:** default to legacy, and switch to v1 for the rest of the
  session once the peer's `hello` announces `"protocol": 1`. A legacy peer never sends that
  field, so an old build keeps receiving exactly what it does today.
- Android only ever speaks v1 — it has no legacy peers by definition.
- Keep the legacy path in a single `LegacyWireCodec.swift` with no other callers, so the
  eventual deletion is one file plus the sniff branch.
- Golden fixtures must cover **both** formats, and the matrix in §9 gains an
  old-iOS ↔ new-iOS row.

> ### ⚠️ REMINDER — remove the legacy wire format in iOS **2.0**
>
> iOS is at **1.4 (build 33)** as of August 2026 (read from the pbxproj —
> `PROGRESS.md`'s version notes are stale). The legacy derived-`Codable` format is
> transitional and should be deleted at the next **major** version bump — **2.0**.
>
> **To remove:** delete `LegacyWireCodec.swift`, drop the format sniff in `WireCodec`,
> always write v1, and raise the minimum accepted `protocol` to 1. Anyone still on a
> pre-dual-format build must then update — a normal expectation across a major version.
>
> *If you are reading this while cutting 2.0, this is the reminder. Do it now.*

---

## 3. `:core` — engine and rules port

Direct translations, Swift → Kotlin:

| Swift | Kotlin | Notes |
| --- | --- | --- |
| `Card.swift` (145) | `Card.kt` | `Suit` string enum, `Rank` int enum — mechanical |
| `CribbageModels.swift` (123) | `Models.kt` | `Deck`, `SeededGenerator` (SplitMix64), `PlayerID`, `Seat`, `ScoringMode`, `GamePhase` |
| `GameState.swift` (264) | `GameState.kt` | `GameState`, `PlayerSnapshot`, `PlayedCard`, `Claim`, `PegEvent` |
| `CribbageScorer.swift` (296) | `CribbageScorer.kt` | Pure functions. Fifteens, pairs, runs, flushes, nobs, pegging — **done** |
| `CribbageEngine.swift` (342) | `CribbageEngine.kt` | The referee state machine — **done** |

Swift value semantics → Kotlin `data class` with `copy()`. `Set`/`Map` map directly.
Port SplitMix64 exactly (it's specified arithmetic, unlike `shuffle(using:)`).

**Testing — done.** Two differential fixture corpora, both emitted by programs compiled against
the real Swift sources and committed to *both* repos:

- `fixtures/scorer-v1/` (`tools/generate-scorer-fixtures.sh`) — 11,048 show cases and 4,815
  pegging cases. The show corpus is exhaustive over all 6,175 five-rank multisets a deck can
  produce, which covers the fifteens/pairs/runs logic completely, plus an exhaustive suit-structure
  section for flushes and the crib's flush rule, random deals, and named extremes (the 29,
  double-double runs, nobs). Compared as ordered flag lists including the `detail` wording.
- `fixtures/engine-v1/` (`tools/generate-engine-fixtures.sh`) — 150 scripted games, 3,597 steps,
  ~20% of them illegal intents that must be refused. Each script fixes the deck, hands and starter
  explicitly and records the **whole state after every step**, plus each handler's boolean return.
  Turn order and rejection are what matter here: a disagreement there deadlocks a real game rather
  than mis-scoring it.

Out of scope for the engine corpus by design: anything that reshuffles (`dealNewHand`, the
cut-for-deal tie recut, `playAgain`), since §0.3 accepts that the platforms don't share a shuffle.
Those are covered structurally by `EngineTest` on the Kotlin side.

Both suites were mutation-checked rather than trusted for passing first time.

---

## 4. Transport

### 4.1 Shared design

Kotlin mirror of the existing Swift protocol:

```kotlin
interface GameTransport {
    val isHost: Boolean
    val events: Flow<TransportEvent>   // Connected / Reconnecting / Disconnected / Received
    suspend fun send(message: GameMessage)
    fun reconnect(force: Boolean = false)
}
```

`AsyncStream` → `Flow`, `@MainActor` → a `CoroutineScope` on `Dispatchers.Main.immediate`.
Carry over the behaviours `MultipeerSession` learned the hard way — they are not incidental:

- **Outbox buffering** (cap 200) so a tap during a connectivity gap isn't lost.
- **Forced rebuild on foreground**, because the OS reports a dead link as live for ~30s.
- **Deterministic single-inviter rule** so both sides don't race two half-open connections.
- **Ghost-peer collapsing by display name** in the discovery list.

### 4.2.1 A hosting deadlock, found in a real game

Reported as: the Android host stuck on "Waiting for a player to join…", the iOS guest stuck on
"Connecting…". Two independent faults, both in `LanTransport`, either enough on its own:

- **`startHosting()` was not idempotent.** The listener takes an *ephemeral* port and advertises it,
  so calling it again moved the port. A guest that had already resolved the first advertisement then
  dialled a port nobody was listening on — no refusal it could act on, so it waited. The trigger is
  nothing exotic: a second tap on "Host a game", which this app made likely, since its buttons were
  hard to press until the inset work above. `startBrowsing()` had the same shape and got the same
  guard. Covered by a `:core` test that fails without the fix.
- **The listener took a fresh ephemeral port every time it restarted.** This is the one that was
  still biting after the first two fixes, and the device trace showed it in three lines:

  ```
  14:31:15.798  accepted 192.168.68.63
  14:31:15.800  paired with 192.168.68.63:59346
  14:31:15.967  hosting: listening on 33109 ...      <- 167ms later, a *different* port
  ```

  Any drop sends the host back to listening, and it came back on a new port while the guest was
  still dialling the one it had resolved. Nothing is listening there, the guest gets no refusal it
  can act on, and both sides wait. The port is now chosen once and kept for the life of the
  transport (`reuseAddress`, since the old socket is in TIME_WAIT), falling back to a fresh one only
  if the port is genuinely taken. Covered by a `:core` test, and confirmed on the phone: the same
  43983 before and after a drop.

- **The accept job accepted exactly once and then returned.** Any connection the host declined — a
  straggler, or one arriving between listener generations — ended it, and the *next* connection was
  never accepted at all. It still completes at the TCP level, because the kernel finishes the
  handshake into the backlog, so the guest believes it is connected and waits for a reply from an
  app that has stopped listening. `NWConnection` makes that likely rather than theoretical: Bonjour
  resolves to both IPv6 and IPv4 and Network.framework races them, so the host can be handed a
  connection the guest is about to abandon. It now keeps accepting until one sticks, and fails
  loudly rather than advertising a port nothing is reading.

`tools/run-lan-interop.sh` passed throughout — the happy path was never the problem, which is
worth remembering about that harness: it proves the protocol and the framing, not the races, and it
runs over loopback where none of this happens.

**`LanTransport.trace`** exists because of this hunt: a hosting failure in the field otherwise
leaves no evidence at all. `:core` has no Android dependency, so it is a lambda the app points at
logcat in debug builds — `adb logcat -s PairForTwoLan`. Note `Log.i`, not `Log.d`: the test phone
drops app debug logs entirely, which cost an hour on its own.

**A warning for the next person who tries to reproduce this on a Mac.** Network.framework in an
unbundled command-line binary is blocked from the local network by macOS's privacy controls — every
connection reports `waiting: Network is down`, including to an IP that `nc` reaches happily. The
Swift harness only works because it pairs over loopback. Reproducing an iOS↔Android pairing bug
needs a real iOS build, or the Android trace above.

### 4.2 Android — `LanTransport`

- **Service type: `_pairfortwo-lan._tcp`**, not `_pairfortwo._tcp` as originally written here.
  iOS's `MultipeerSession` uses `serviceType = "pairfortwo"`, which makes MultipeerConnectivity
  register exactly that Bonjour type — so sharing it would make this browser discover Multipeer
  advertisements, and a guest would open a raw TCP socket to a peer speaking MC's own protocol.
  Caught while building the iOS side; both platforms now use the `-lan` type.
- **Where the code lives — amended.** Only *discovery* is Android-specific. Sockets are plain JVM
  and run unchanged on Android, so the socket state machine, the outbox, the reconnect logic and
  NDJSON framing live in **`:core`** (`net/LanTransport.kt`, `net/Ndjson.kt`), behind a
  `LanDiscovery` interface that `:app` implements with `NsdManager`. That is what lets the whole
  transport be tested over real loopback sockets on the JVM, with no emulator and no second
  device — including drop-and-reconnect, which is otherwise painful to exercise at all.
- **Discovery:** `NsdManager` register + discover.
- **Connection:** host opens a `ServerSocket` on an ephemeral port and advertises it; guest
  resolves and connects. `TCP_NODELAY` on — these are small latency-sensitive messages.
- **I/O:** Okio buffered source/sink on `Dispatchers.IO`, one coroutine per direction.
- **Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`. Hold a
  `WifiManager.MulticastLock` while discovering — mDNS is unreliable without it on many
  devices. NSD does *not* need location or `NEARBY_WIFI_DEVICES` (those are for Wi-Fi
  Aware/Direct).
- **Known rough edges to test explicitly:** `NsdManager`'s pre-API-34 implementation is
  historically flaky (resolve races, stale callbacks — serialise resolves and retry);
  many public/guest Wi-Fi networks enable AP isolation and will silently block
  peer-to-peer traffic entirely. The connect screen needs a real "couldn't reach the other
  device — check you're on the same network" state, not a spinner forever.

### 4.3 iOS — `LANTransport.swift` (new)

- `NWListener` + `NWBrowser` with a Bonjour service descriptor, same `_pairfortwo-lan._tcp`.
- `Info.plist`: `NSLocalNetworkUsageDescription` and `NSBonjourServices`
  (`_pairfortwo-lan._tcp`). Without these, iOS 14+ silently blocks discovery.
- Local-network permission prompt appears on first use — the connect screen should explain
  *before* triggering it.

### 4.4 iOS connect screen — merged discovery

The iOS device now advertises over both Multipeer and Bonjour, so a nearby iPhone may
appear twice. **Dedupe by player name and silently prefer Multipeer** (it works without a
network). The user never sees or chooses a protocol; Android peers simply show up in the
same list, optionally with a small platform glyph.

---

## 5. Feel — sound and haptics

### 5.1 Sound

iOS synthesizes nine effects in `GameFeedback.swift` (click, tick, flip, whoosh, riffle,
ding, chime, go, firework) with deterministic noise, so they're identical every run.

- **Done, and stronger than "port the DSP".** The synthesis was *extracted* from
  `GameFeedback.swift` into a Foundation-only `SoundSynthesis.swift`, which iOS still uses at
  launch and which `tools/render-sounds.sh` compiles into a command-line renderer. The nine WAVs
  in `app/src/main/res/raw/` therefore come from the very code iOS plays, rather than from a
  re-implementation that could drift. The extraction was verified byte-identical against the
  pre-refactor code, effect by effect.
- `SoundPool`, `maxStreams = 8`, `USAGE_GAME` / `CONTENT_TYPE_SONIFICATION`.
- Celebration volley: the same 14 pops with randomised rate `0.88–1.22` and volume
  `0.65–1.0`. `SoundPool.play()` takes both, so this ports exactly.
- **Deliberate divergence:** iOS uses an `.ambient` session, so the mute switch silences
  effects. Android has no mute switch and games conventionally play through the game
  stream, so only the in-app "Sound effects" toggle gates playback. Same call StarBattle
  made; will be noted in code.

### 5.2 Haptics

The least faithful part of the port, unavoidably. Core Haptics has an *intensity* **and a
*sharpness*** axis plus continuous parameter curves; Android has amplitude only. Target
"reads as the same gesture", not "identical".

Mapping strategy for the eleven `GameFeedback.Action` cases plus win/lose/slider-tick:

- **Transients** → `VibrationEffect.Composition` primitives (API 30+): `PRIMITIVE_CLICK`,
  `TICK`, `LOW_TICK`, `THUD`, `QUICK_RISE`. Sharpness picks *which* primitive (sharp →
  `CLICK`/`TICK`, dull → `THUD`/`LOW_TICK`); intensity becomes the primitive scale.
- **Continuous events** → `createWaveform(timings, amplitudes, -1)`.
- **Parameter curves** (`deckLift`'s rising drag, the win intensity envelope) → discretise
  into ~10 ms amplitude steps.
- **Degradation ladder**, probed at construction exactly as StarBattle does it:
  `areAllPrimitivesSupported` → composition; else `hasAmplitudeControl` → waveform; else
  plain on/off pulses; else silent.
- **Honour the system haptics setting** (`Settings.System.HAPTIC_FEEDBACK_ENABLED`) on top
  of the app's own toggle. iOS has no equivalent, but ignoring it on Android is wrong.
- **Win celebration** scaled by skunk level (4.0s/46 bursts → 5.5s/72 → 7.0s/100). Chaining
  segments turned out to be unnecessary: the two continuous layers and the intensity curve put
  this on the waveform path, and `createWaveform` has no composition-length cap — 7 s at the
  renderer's 10 ms step is 700 entries.

### 5.3 Why none of it was firing

Reported from a real phone as "I'm not feeling any haptics", and it turned out to be two faults
stacked, either of which alone would have been enough:

1. **The app never declared `android.permission.VIBRATE`.** Without it every `Vibrator.vibrate` call
   is dropped — no crash, no log line — so the patterns, the renderer and the degradation ladder
   were all correct and none of it could reach the motor. `dumpsys package` showed the permission
   simply absent; `dumpsys vibrator_manager` showed not one vibration ever attributed to the app.
2. **The table only ever called `GameFeedback` for scoring and the win.** Cut, deal, discard,
   card play, go, the starter reveal and Continue had no call site at all — the wiring note in this
   plan was ticked off before the actions were connected. They are now, through a `LocalFeedback`
   composition local, because each phase's play area is a private composable several levels down.

Two smaller things came out of the same session: 31 is felt via a `LaunchedEffect` on the running
count, since it falls out of the count and has no button to hang off; and every effect is now played
with `VibrationAttributes.USAGE_TOUCH`, because an unattributed vibration is filed under
`USAGE_UNKNOWN` and the per-usage intensity sliders can scale it to nothing.

Verified on the device, not by eye: `dumpsys vibrator_manager` records the cut tap as
`usage: TOUCH | com.jirofeingold.pairfortwo | played: [Step=0ms…Step=120ms(amplitude=1.00)…]`.

Files: `feel/SoundEffects.kt`, `feel/HapticsController.kt`, `feel/GameFeedback.kt`
(the unified `play(action)` entry point matching iOS).

---

## 6. UI — Compose, and where to be Android

Guiding rule: **the game table is the brand and should look the same on both platforms; the
chrome around it should feel native.**

| iOS | Android | Treatment |
| --- | --- | --- |
| `GameTableView` (1119) | `GameTableScreen.kt` | **Done, and level with iOS `89adb97`** (see §6.1). Every phase plays through on a device. Proportional layout via `BoxWithConstraints`, from the same ratios the Swift's `GeometryReader` computes. Two deliberate structural changes: the top band **wraps to a cap** rather than being pinned and clipped, because Android text measures taller and the scoreboard's digits were sliced off whenever the flag chips appeared; and the action rail **reserves a flag band** above its button instead of floating the flags over a rail-centred button (§6.1). |
| `CardView` (121) | `CardView.kt` | **Done**, including `RankSuitTile`. Suit symbols are *drawn paths*, not the ♠♥♦♣ characters: Android maps those to whichever font the vendor chose, the metrics differ sharply from SF's, and some devices render them as colour emoji. The first device screenshot showed glyphs thick enough to collide with the centre pip. |
| `ScorePanel` (494) | `ScorePanel.kt` | **Done.** Custom track shapes, `PointsSlider` and the skunk marks, all on `Canvas`. Compose's `PathMeasure` gives the position on the track directly, so the marks don't need iOS's trick of approximating it from the bounding box of a tiny trimmed slice. |
| `WinnerOverlay` (405) | `GameOverOverlay.kt` | **Done.** Fireworks and confetti are `Canvas` particle systems on a single frame clock, rather than 120 individual animations. `.ultraThinMaterial` has no Compose equivalent, so the table itself is blurred behind the card — a no-op below API 31, where the scrim alone still reads. |
| `LoserOverlay`, `ScoreFlagsView`, `PlayPileView`, `HandView` | direct ports | **Done.** `LoserOverlay` only appears on a networked loser's device, so it is so far untested on screen. |
| `ScoringReplayView` | `ScoringReplay.kt` | **Done**, with `replayBeforeWin`, `GameFeedback.playScoreTick` and the pre-win gating from §6.1. **No watchdog**: iOS needs one because SwiftUI can re-create a view and cancel its `Task`, while a `LaunchedEffect` keyed on the score log is only cancelled by leaving composition or by the key changing — and the log is final once the game is over. |
| `SettingsView` | `SettingsScreen.kt` | **Done, minus the settings with nothing to act on yet** (name/colour, scoring replay). **Android-native:** Material 3 `ListItem` + `Switch`, back arrow. A small `TopAppBar`, not the `LargeTopAppBar` sketched here — the game is landscape-locked and a large bar spends a third of the height on its own title. Reached from a gear control on the table, as on iOS. |
| `HelpView`, `OnboardingView` | `HelpScreen.kt`, `OnboardingScreen.kt` | **Done.** Help keeps iOS's trick of illustrating itself with the app's *real* cards and scoring control, so the slider in it actually works and a picture can't go stale. Onboarding is a `HorizontalPager` with a dot indicator, and keeps the first-run scoring picker and the random colour. Wording drops what Android hasn't got: no Play online, no Bluetooth, and no check-my-count or scoring replay until those are ported — documenting a button that isn't there would be worse than saying nothing. |
| `ConnectView`, `InvitePlayersView` | `ConnectScreen.kt` | **Done.** One transport instead of iOS's two, so there is no merged discovery and no protocol for the player to be aware of — an iPhone advertising over Bonjour is just another row. Has the explicit network-trouble state §4.2 asks for, since AP isolation is otherwise a spinner forever. |
| `RootView`, `ContentView` | `RootScaffold.kt` | **Done.** Menu keeps the felt look. **No "Play online" entry**, and **no pass-and-play entry** — iOS removed single-device play ("this is a two-phone game") and the two apps should offer the same thing. `GameViewModel.loopback` stays for the JVM tests. iOS's Help button waits on `HelpScreen`. |
| `GameViewModel` (31 KB) | `core/GameViewModel.kt` | **Done**, and deliberately in `:core`, not `:app`: it imports no Compose — the Android form of the project's "view models never import SwiftUI" rule — and a whole game plays out in a JVM test as a result. |
| `MatchmakerView`, `GameCenterManager`, `GameCenterTransport` | — | **Not ported.** |

Android specifics to get right:

- **Predictive back** — `BackHandler` throughout; back must not silently abandon a live game
  (confirm, matching the existing quit dialog).
- **Edge-to-edge** with proper `WindowInsets` — mandatory on Android 15+.
- **No Material You dynamic colour.** The felt/gold palette and the twelve player themes are
  brand identity and must match iOS; dynamic colour would break parity. Fixed dark theme.
- **Landscape only** (`android:screenOrientation="sensorLandscape"`). The table is designed for
  it — the Swift's own comment is "Landscape: top ~1/3 is the scoreboard… bottom ~2/3 is the
  shared play area" — and in portrait the bands stretch into mostly empty felt. `sensorLandscape`
  rather than `landscape` so the device can be held either way up, which matters for a game two
  people pass between them.
- **Tablets and foldables** — the Android answer to your iPad rule. `WindowSizeClass`; the
  table scales proportionally already, so the work is in the chrome. Test on a tablet
  emulator and a fold posture. **Note:** from Android 16, an app targeting SDK 36 has its
  orientation restriction *ignored* on large screens (smallest width ≥ 600dp), so a tablet may
  still present portrait despite the lock. Worth confirming on real hardware.
- `core-splashscreen` for the existing splash art.

### 6.2 Phase 8 — what the platform pass actually found

- **Tablet.** Verified on a 1280×800dp API 36 tablet: the rail widens to 30% (capped at 420dp), the
  cut cards halve, and the pegging pile drops down from the top with the hand centred below it. The
  `hSizeClass == .regular` branches read as **height ≥ 600dp**, which is `sw600dp` in a
  landscape-locked app.
- **Orientation on large screens.** PLAN.md warned that from Android 16 an app targeting SDK 36 has
  its orientation restriction ignored at sw ≥ 600dp. The lock held on the tablet AVD, but forcing
  `user_rotation` on a fixed-landscape emulator panel proves little — **still worth confirming on
  real tablet hardware.**
- **Edge-to-edge.** Every screen pads to `WindowInsets.safeDrawing`. The table applies it *after*
  its background, so the felt bleeds behind the system bars while the card budgets — which are
  computed from those same constraints — stay clear of a landscape cutout.
- **Predictive back.** `android:enableOnBackInvokedCallback="true"`, plus `BackHandler` on Settings,
  Help, the connect screen and the table. The table's confirms before abandoning a live game; the
  menu deliberately has none, since back from the root screen should leave the app.
- **Accessibility.** The scoring control was the real gap: a hand-drawn track and a `pointerInput`
  are invisible to a screen reader, and it is the control the whole game turns on. The slider now
  declares `progressBarRangeInfo` and a `setProgress` action, so TalkBack can both read and set the
  staged points; the +1 button carries a click label that says which of its two jobs it will do; and
  the panel has one spoken summary instead of a pile of unlabelled shapes. Touch targets under 48dp
  (undo, the colour swatches) keep their drawn size and gain a legal target via
  `minimumInteractiveComponentSize()`.
- **Every edge-anchored control uses `WindowInsets.safeContent`**, not `safeDrawing`. `safeContent`
  is `safeDrawing` plus the gesture strips, which is exactly the difference between "not drawn
  under something" and "reliably touchable". Swept after the connect screen's Back turned out to
  have the same fault as the table's chrome: Back on the connect screen, Skip and Continue in the
  onboarding tour, the scoring replay's buttons, the menu's help glyph, and the Settings and Help
  app bars. The table is the one exception and does it by hand — see below — because padding the
  whole screen there costs 43dp of card height.

- **Chrome buttons — the real fix was *where*, not how big.** Quit, help and settings were hard to
  press, and two rounds of enlarging them didn't help. They are now a 40dp disc inside a real 56dp
  clickable (26dp glyph in 56dp on the menu) rather than a drawn-size clickable leaning on
  `minimumInteractiveComponentSize()`'s out-of-bounds interception — but that was not the problem.

  `dumpsys window` on the test phone gave the answer. In landscape it reports
  `mandatorySystemGestures` of **70px (43dp) along the top** — swipe down for the hidden status bar
  — and **78px (48dp) down the right**, the navigation bar's edge. Help and settings sat entirely
  inside the right strip and all three sat inside the top one. *Mandatory* means exactly that:
  `systemGestureExclusion()` is ignored there, so every press was arbitrated against a system
  gesture and a thumb that moved a pixel lost. The controls are now inset past
  `WindowInsets.mandatorySystemGestures` — and so are the Settings and Help back arrows and the
  menu's help glyph, which sat in the same top strip and were reported as hard to leave by.

  **Where they ended up: on the scoreboard row.** Two attempts came first and both cost height.
  Floating them in the band's corners and insetting them past the strip pushed the whole band down
  by 43dp; reserving a taller first row for them instead kept the band in place but spent the same
  height on whitespace above the scoreboard. They now sit *in* the band, level with the score
  panels — quit at one end, help and settings at the other. That row starts well below the gesture
  strip anyway, so the controls are clear of it for nothing, and the band is back to being as short
  as its content: one line of coach banner, then the scoreboard.

  The band's height is now stated as [TOP_BAND_HEIGHT] rather than taken as 42% of the screen, and
  the play area's budget follows it. On the test phone that is 142dp instead of 186dp — 44dp
  straight into the cards.
  They also gained a ripple — with no indication at all, a press that landed looked identical to
  one that didn't, which is its own reason to press again.

  **Deliberate divergence from iOS's 32pt discs** — see `ControlButton` in `GameTableScreen.kt`.
- **The skunk markers on the score track sit *on* the ring now.** They are drawn at 60 and 90
  points round the panel's edge, and were centred on the text layout rather than on the ink: the
  negative tracking that overlaps a pair applies after the last glyph too, so the measured width is
  a letter-space short and halving it pushed the mark right of the line. The score track also moved
  outside the panel's rounded clip, because a mark that straddles the ring had its top half sliced
  off at the panel edge — which is what made the double skunk read as sitting under the line.

- **Horizontal insets are applied symmetrically.** The test phone's selfie camera puts a 47dp cutout
  inset on the left and nothing on the right, so padding each side by its own inset shunted the
  whole scoreboard 47dp right — visibly off-centre on a symmetric composition. Both sides now take
  the larger inset, which costs a little felt on the clear side and keeps the table centred on the
  screen. Vertical insets are still per-side; only the horizontal pair is a visual axis.
- **The pegging hand was budgeted wrong.** Its cards came out of the discard hand's width — a
  six-card fan — despite pegging never holding more than four, and its vertical budget reserved
  44dp at a 2.15 ratio for a stack that is really a label row, a pile card, an 8dp gap and the
  hand. Rebudgeted from those parts, with the pile down from half the hand card to a third since
  it is the secondary element. On the test phone the pegging cards went from 99×143dp to 110×160dp.

- **Four table details, from playing it on the phone rather than reading the Swift.**
  - The **deck was clipped** on the lifted half of the starter cut. `DeckPile` sized itself to one
    card and let the other three spill outside its bounds; that draws fine on its own, but the
    lifted half carries `Modifier.alpha`, and an alpha layer clips to its layout bounds — so the
    top and right of the stack were sliced off. The pile is sized to its whole footprint now, which
    also makes the tap target the deck you can actually see.
  - The **deck's pulse was missing.** iOS breathes a tappable deck at 1.04x on a repeating 0.8s
    ease-in-out; ported. Verified by frame-grabbing the device rather than by eye — the pile's
    bounding box swings 183→191px.
  - The **crib stack drew all four cards**, stepping 3dp down and right each time, which pushed it
    into the hand below once the play area got taller. It draws two now: it is an indicator that a
    crib exists, not a count. **Deliberate divergence from iOS.**
  - The **crib's gold backing at the show was never ported** — only the badge above it was. iOS
    wraps the crib's cards in a gold-tinted, gold-stroked panel so it is obvious the crib is being
    counted and not another hand, which is exactly the moment that distinction matters.

- **Foldables — tested.** A `pft_fold_36` AVD (Pixel 9 Pro Fold, API 36.1) now exists; both postures
  were exercised.
  - **Folded** — the 994×443dp cover panel. The landscape lock holds, and the menu, table and chrome
    read exactly as on a phone. This is the posture the layout was designed for.
  - **Unfolded** — the 851×883dp inner panel. **The landscape lock is ignored**, precisely as this
    plan predicted for SDK 36 at sw ≥ 600dp, so the app presents portrait. Nothing clips or breaks:
    the near-square screen means the table still lays out and plays, with the scoring band across
    the top and the action rail down the side. It is simply loose — a wide empty middle where a
    landscape phone has cards. Making the table *good* on a near-square screen is a layout question
    in its own right and has not been attempted.
  - **A posture change used to end the game.** `MainActivity` declared no `configChanges`, so
    unfolding moved the app to the other display, Android recreated the activity, and the running
    game — which `RootScaffold` holds in composition — went with it: both players dropped back to
    the menu. The manifest now handles `screenSize|smallestScreenSize|screenLayout|orientation|
    density` itself. Verified by watching the `ActivityRecord` identity across a fold and an unfold:
    the same instance survives both, where before the fold restarted it. Worth knowing this was
    never foldable-specific — any config change that recreates the activity would have done it.

### 6.1 Keeping up with iOS

The iOS app does not stand still while this port is built, so the Android side records **which iOS
commit it is level with** rather than pretending the two were written at once.

**Level with iOS `89adb97` (2 August 2026).** iOS shipped 1.5 and then reworked the table across
21 commits; all of the portable ones are in. What changed here:

- **An action rail on every play phase.** The cards fill and centre the play column; the prompt,
  status and primary button live in a fixed-width trailing column (156dp, or 30% up to 420dp on a
  tablet). Nothing stacks below the cards any more, which is what kept pushing the button off the
  bottom of a short landscape phone. `hSizeClass == .regular` reads on Android as **height ≥ 600dp**
  — in a landscape-locked app the height *is* the smallest width, so that matches `sw600dp` and
  classifies a landscape phone as compact, exactly as iOS does.
- **Scoring flags moved from the top band into the rail**, as a vertical chip column, which gives
  the scoreboard back its height.
- **`RankSuitTile`** — a compact rank-over-suit tile for the standalone "The Cut" card and the
  played pegging cards, with the overlap loosened from 0.55 to 0.28 of a card so every rank stays
  readable. Full pip cards return for the show, where the cut is counted into a hand.
- **The pegging hand keeps a reserved slot**, so the layout doesn't shift as the last card is
  played; the pile drops down from the top on a tablet.
- **Skunk marks** (two 🦨 at 60, one at 90) replace the radial ticks; the start tick is fainter.
- **Roomier auto-mode scores** and a clear capsule divider between them.
- **Play Again checks the opponent is reachable first**, via the new `GameViewModel.opponentAvailable`
  — the view model drops intents while disconnected, so the tap would otherwise do nothing at all.
- **`setScoringMode` compares against the game's mode, not the device's**, fixing a guest whose
  flags kept showing after it switched to player responsibility.
- The win/lose overlays gained a way home, and swap their primary button for "Back to menu" when a
  rematch is impossible.

**One deliberate divergence.** iOS floats the flag column over a rail-centred button. The same chips
and prompts measure taller on Android, and on the show screen the flags landed on top of "Count it on
your slider, then Continue". Android reserves a fixed 96dp flag band and centres the action *below*
it — the button sits a little lower than the cards' centre, but it can never be covered, and it
still doesn't move when flags appear. **The iOS side is worth the same treatment**, alongside the
`confirmRelease` fix noted in §7.

**Deferred, because it belongs to an unported screen:**

- The `Check` capsule in the show rail, which waits on the check-my-count overlay — the only piece
  of the §6.1 catch-up still outstanding.
- iOS's `MultipeerSession` pairing-retry change is already the behaviour `LanTransport` shipped
  with — a reconnecting guest re-browses and re-invites on a timer (`rebrowseIntervalMs`). Nothing
  to port.

---

## 7. Persistence

Straight mapping of `Persistence.swift`:

- Host's full `GameState` → JSON file in `filesDir` (iOS: Application Support), atomic write.
- Resume marker (active / isHost / summary) + settings → **DataStore Preferences**
  (iOS: `UserDefaults`/`@AppStorage`).
- Same semantics, including "a guest deletes any stale state file so `hasSavedState`
  reliably identifies the true host on resume."

Settings keys to carry over: `soundEnabled`, `hapticsEnabled`, `playerName`, `colorID`,
`cardBackID`, `confirmRelease`, scoring mode, onboarding-seen.

**Done.** `settings/SettingsStore.kt` is a DataStore Preferences store keyed identically to iOS's
`@AppStorage`, and `persistence/AndroidGamePersistence.kt` is the rest: the host's `GameState` to a
file in `filesDir` (written to a sibling and renamed, so a kill mid-write can't leave a truncated
game), the resume marker to DataStore, and a guest deleting any stale state file so the file
reliably identifies the one true host. Only `replayBeforeWin` is still missing, because
`ScoringReplay` isn't ported and there is no point storing a setting nothing reads.

**One departure from the sketch above, and it is load-bearing.** `GamePersistence`'s methods are
synchronous fire-and-forget — the view model calls them mid-message — while a file write shouldn't
touch the main thread and DataStore is suspend-only. Launching a coroutine per call would leave
their *completion* order undefined, and a `clear()` overtaking the `save()` before it resurrects a
finished game as a phantom "Rejoin game". Every read and write therefore goes through a single
queue drained by one writer coroutine. The marker stays in DataStore as planned; the queue is what
makes that safe.

The saved file is Kotlin's encoding of `GameState`, not Swift's, and that is fine permanently: it
never leaves the device. Only the redacted snapshots on the wire have to agree (§2).

**Resuming picks the host by who holds the state**, not by the marker's recorded role — iOS's own
rule from `RootView.onConnected`, applied one step earlier here because `LanTransport` has no
rendezvous mode in which both sides advertise *and* browse. The device with the file hosts; the
other browses and is resynced.

`confirmRelease` (default on). **Deliberate divergence:** iOS applies it to the local panel only
(`isLocal ? confirmRelease : false`), so in pass-and-play the two panels on one screen get
different gestures — one stages for the +N button, the other scores the moment the thumb lifts.
Android applies it to every panel. The iOS side is worth the same fix.

---

## 8. Assets

- **Card backs** — copied from the iOS asset catalog as WebP. **Deliberate divergence:** Android
  scales them to *fill* the card, where iOS fits the whole art and blurs a copy behind it to cover
  the margins. A back that reaches its own edges reads as a card; a fitted one reads as a picture of
  one. Worth bringing across to iOS.
- **App icon** — done. `tools/generate-icon.py` derives every density from the iOS `icon_1024.png`,
  so both stores show the same art. The foreground sits at 72 of the 108dp canvas, which is what a
  mask actually reveals: at full bleed the mask ate the tops of both cards.
- **Splash** — done, but *not* `splash.png`. That art is a 1534×704 banner and the Android splash
  slot is a masked square, so the launch screen is the app icon on felt via `core-splashscreen`,
  which is what iOS's launch screen amounts to anyway.
- **Strings** — everything into `strings.xml` from the start (the iOS app hardcodes English;
  no `.xcstrings` exists yet). Costs nothing now, and makes both apps translatable later.

---

## 9. Testing

- **JVM unit tests** (`:core`): scorer differential fixtures vs Swift, engine state-machine
  transitions, protocol round-trips.
- **Protocol golden tests** in both repos (§2) — the critical one.
- **Manual matrix**, all four combinations plus failure modes:

| | iOS | Android |
| --- | --- | --- |
| **iOS** | Multipeer (regression — must be unchanged) | LAN |
| **Android** | LAN | LAN |

  Per pair: full game (cut for deal → discard → starter cut → pegging → three shows → win),
  each side hosting, backgrounding both sides, Wi-Fi drop and recovery, force-quit and
  rejoin, quit propagation, and all three scoring modes.
- **Build/test commands** — `./gradlew :core:test :app:assembleDebug`; iOS side per your
  `CLAUDE.md` (`xcodebuild -scheme "Pair for two" …`, iPhone 16 + iPad Pro 13-inch M4).

---

## 10. Order of work

Sequenced so the riskiest, most cross-cutting thing is proven first.

| Phase | Work | Rough size | Status |
| --- | --- | --- | --- |
| **1** | Project setup, modules, `com.jirofeingold.pairfortwo`, CI-able build | small | ✅ done |
| **2** | **`PROTOCOL.md` + DTO layer on both sides + golden round-trip tests + iOS dual-format compat (§2.1)** | medium | ✅ done |
| **3** | `:core` port — models, scorer, engine + differential fixtures | large | ✅ done |
| **4** | **LAN transport on both platforms + merged iOS discovery.** Prove iOS↔Android with a throwaway harness before any UI exists | large | ✅ done — interop proven, see below |
| **5** | Feel — render WAVs, `SoundEffects`, `HapticsController` | medium | ✅ done, and now actually verified on hardware — it had never once fired, see §5.3 |
| **6** | UI — game table first (the bulk), then overlays, then chrome | large | ✅ done — table (level with iOS `89adb97`, §6.1), overlays, Settings, menu, connect, help, onboarding and the scoring replay. **Check-my-count is the one iOS screen with no Android counterpart**; it is a small overlay over the show, not a screen in its own right |
| **7** | Persistence, resume, lifecycle | medium | ✅ done — settings, saved game, resume marker and the foreground/background hooks (§7). Untested across two devices, which is the same gap as phase 4. |
| **8** | Tablet/foldable pass, edge-to-edge, predictive back, accessibility | medium | ✅ done, foldables included — see §8.1. Open: the table is *usable* but loose on a near-square unfolded screen, and real tablet hardware still hasn't confirmed the orientation behaviour |
| **9** | Play Console setup, signing, icon/splash, store listing, release | medium | code side done (icon, splash, signing config); the rest needs a Play Console account — see `RELEASE.md` |

Phases 2 and 4 are where this project succeeds or fails. Phase 6 is the most *hours* but
the least risk. **Recommend proving iOS↔Android connectivity end-to-end at the close of
phase 4** — a Kotlin CLI harness driving a real game against an iPhone, before a single
Compose screen exists.

**Done, and better than planned.** `Network.framework` works on macOS, so the *shipping*
`LANTransport.swift` can be built as a command-line process and paired with the *shipping* Kotlin
`LanTransport` over real Bonjour and a real TCP socket — on the dev machine, with no devices at
all. `tools/run-lan-interop.sh` (iOS repo) does this in both directions: Swift hosts while Kotlin
joins, then Kotlin hosts while Swift joins. Each run sends all 16 golden fixtures one at a time,
then as an unbroken burst, and checks every echo. Because it needs no hardware it is a regression
test rather than a one-off ceremony, and it can run whenever either transport changes.

Still worth doing once on real hardware before shipping: an actual phone-to-phone run over Wi-Fi,
which is the only way to exercise NsdManager itself, the multicast lock, and AP isolation.

---

## 11. Risks and open items

1. **AP isolation on public Wi-Fi** silently blocks LAN play. Mitigation: clear error
   state + a "use a personal hotspot" hint in Help.
2. **`NsdManager` flakiness** below API 34. Mitigation: serialise resolves, retry with
   backoff, multicast lock, and a manual "enter IP" escape hatch if it proves bad.
3. **Haptic fidelity** cannot fully match Core Haptics (no sharpness axis). Accepted.
4. **Protocol drift** once both ship. Mitigated by the version handshake (§2) — worth
   getting right now, since a v2 will eventually happen.
5. **iOS regression risk** — this plan touches shipped iOS code (Codable → DTOs, new
   transport, merged connect UI). *Resolved:* the new build speaks both wire formats and
   follows the peer, so old and new iOS builds interoperate (§2.1). The cost is carrying
   dead code until **2.0**, when the legacy path is deleted — see the reminder in §2.1.
6. **No Android release convention exists yet** — *partly resolved.* `RELEASE.md` now records the
   versioning rule, the signing setup and the build commands. What remains genuinely needs your
   Google account: a Play Console entry, the store listing, a privacy policy URL, the data safety
   form, and `fastlane supply` if it should be as scripted as iOS. **No upload key exists yet.**

*Repo location (formerly item 7) is resolved — see §1.*

---

## Explicitly out of scope for v1

- Online / internet play on Android (needs a relay server — separate project).
- Play Games Services of any kind.
- BLE transport (no-Wi-Fi cross-platform play) — the transport interface leaves the seam
  open for a later release.
- Localisation beyond extracting strings.
- Play Games / Game Center achievements or leaderboards (neither app has them).
