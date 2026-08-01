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
| `CribbageScorer.swift` (296) | `CribbageScorer.kt` | Pure functions. Fifteens, pairs, runs, flushes, nobs, pegging |
| `CribbageEngine.swift` (342) | `CribbageEngine.kt` | The referee state machine |

Swift value semantics → Kotlin `data class` with `copy()`. `Set`/`Map` map directly.
Port SplitMix64 exactly (it's specified arithmetic, unlike `shuffle(using:)`).

**Testing.** Add a `tools/fixtures` emitter following the StarBattleAndroid precedent:
enumerate several thousand hand/starter/crib combinations, score them in Swift via
`RunCodeSnippet` against `CribbageScorer.swift`, and assert the Kotlin scorer agrees.
Cribbage scoring has a long tail — nineteen-point hands, five-card double runs, his-nobs vs
his-heels, flush-in-crib requiring five — and a differential test against the known-good
Swift implementation is far cheaper than hand-writing cases.

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

### 4.2 Android — `LanTransport`

- **Discovery:** `NsdManager` register + discover, service type `_pairfortwo._tcp`.
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

- `NWListener` + `NWBrowser` with a Bonjour service descriptor, same `_pairfortwo._tcp`.
- `Info.plist`: `NSLocalNetworkUsageDescription` and `NSBonjourServices`
  (`_pairfortwo._tcp`). Without these, iOS 14+ silently blocks discovery.
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

- Port the DSP functions to a small generator script, render all nine to 44.1 kHz mono
  16-bit WAV **once**, and commit them to `app/src/main/res/raw/`. Byte-identical audio to
  iOS, zero runtime cost, zero launch hit.
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
- **Win celebration** scaled by skunk level (4.0s/46 bursts → 5.5s/72 → 7.0s/100) as a long
  composition; Android caps composition length, so chain segments.

Files: `feel/SoundEffects.kt`, `feel/HapticsController.kt`, `feel/GameFeedback.kt`
(the unified `play(action)` entry point matching iOS).

---

## 6. UI — Compose, and where to be Android

Guiding rule: **the game table is the brand and should look the same on both platforms; the
chrome around it should feel native.**

| iOS | Android | Treatment |
| --- | --- | --- |
| `GameTableView` (1119) | `GameTableScreen.kt` | **Faithful port.** Bespoke felt surface, proportional layout via `BoxWithConstraints` (the Swift already uses ratio-driven `GeometryReader`, not size classes). Should be visually indistinguishable. |
| `CardView` (121) | `CardView.kt` | Compose `Canvas`/custom draw. Faithful. |
| `ScorePanel` (494) | `ScorePanel.kt` | Faithful — custom track shapes, `PointsSlider`, skunk ticks. Ports to `Path`/`Canvas`. |
| `WinnerOverlay` (405) | `WinnerOverlay.kt` | Faithful. Fireworks + confetti in Compose `Canvas` driven by `withInfiniteAnimationFrameNanos`. |
| `LoserOverlay`, `ScoringReplayView`, `ScoreFlagsView`, `PlayPileView`, `HandView` | direct ports | Faithful. |
| `SettingsView` | `SettingsScreen.kt` | **Android-native.** Material 3 `ListItem` + `Switch`, `LargeTopAppBar` with a back arrow — not an iOS `Form`, not swipe-back. |
| `HelpView`, `OnboardingView` | `HelpScreen.kt`, `OnboardingScreen.kt` | Compose; onboarding via `HorizontalPager` + `PagerIndicator`. |
| `ConnectView`, `InvitePlayersView` | `ConnectScreen.kt` | **Android-native** Material 3 list, with an explicit network-trouble state (§4.2). |
| `RootView`, `ContentView` | `RootScaffold.kt` | Menu keeps the felt look; Material 3 buttons. **No "Play online" entry.** |
| `MatchmakerView`, `GameCenterManager`, `GameCenterTransport` | — | **Not ported.** |

Android specifics to get right:

- **Predictive back** — `BackHandler` throughout; back must not silently abandon a live game
  (confirm, matching the existing quit dialog).
- **Edge-to-edge** with proper `WindowInsets` — mandatory on Android 15+.
- **No Material You dynamic colour.** The felt/gold palette and the twelve player themes are
  brand identity and must match iOS; dynamic colour would break parity. Fixed dark theme.
- **Tablets and foldables** — the Android answer to your iPad rule. `WindowSizeClass`; the
  table scales proportionally already, so the work is in the chrome. Test on a tablet
  emulator and a fold posture.
- `core-splashscreen` for the existing splash art.

---

## 7. Persistence

Straight mapping of `Persistence.swift`:

- Host's full `GameState` → JSON file in `filesDir` (iOS: Application Support), atomic write.
- Resume marker (active / isHost / summary) + settings → **DataStore Preferences**
  (iOS: `UserDefaults`/`@AppStorage`).
- Same semantics, including "a guest deletes any stale state file so `hasSavedState`
  reliably identifies the true host on resume."

Settings keys to carry over: `soundEnabled`, `hapticsEnabled`, `playerName`, `colorID`,
`cardBackID`, scoring mode, onboarding-seen.

---

## 8. Assets

- **Card backs** — copy the three PNGs (`Royal`, `Celestial`, `Midnight`) from the iOS asset
  catalog; convert to WebP for size.
- **App icon** — adaptive icon (foreground/background layers) derived from `icon_1024.png`.
- **Splash art** — `splash.png`.
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

| Phase | Work | Rough size |
| --- | --- | --- |
| **1** | Project setup, modules, `com.jirofeingold.pairfortwo`, CI-able build | small |
| **2** | **`PROTOCOL.md` + DTO layer on both sides + golden round-trip tests + iOS dual-format compat (§2.1)** | medium |
| **3** | `:core` port — models, scorer, engine + differential fixtures | large |
| **4** | **LAN transport on both platforms + merged iOS discovery.** Prove iOS↔Android with a throwaway harness before any UI exists | large |
| **5** | Feel — render WAVs, `SoundEffects`, `HapticsController` | medium |
| **6** | UI — game table first (the bulk), then overlays, then chrome | large |
| **7** | Persistence, resume, lifecycle | medium |
| **8** | Tablet/foldable pass, edge-to-edge, predictive back, accessibility | medium |
| **9** | Play Console setup, signing, icon/splash, store listing, release | medium |

Phases 2 and 4 are where this project succeeds or fails. Phase 6 is the most *hours* but
the least risk. **Recommend proving iOS↔Android connectivity end-to-end at the close of
phase 4** — a Kotlin CLI harness driving a real game against an iPhone, before a single
Compose screen exists.

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
6. **No Android release convention exists yet** — your `CLAUDE.md` App Store section is
   iOS-only. Android needs its own Play Console + `fastlane supply` (or manual) flow and a
   versioning rule; StarBattleAndroid's `versionCode`/`versionName` bump convention is the
   obvious precedent.

*Repo location (formerly item 7) is resolved — see §1.*

---

## Explicitly out of scope for v1

- Online / internet play on Android (needs a relay server — separate project).
- Play Games Services of any kind.
- BLE transport (no-Wi-Fi cross-platform play) — the transport interface leaves the seam
  open for a later release.
- Localisation beyond extracting strings.
- Play Games / Game Center achievements or leaderboards (neither app has them).
