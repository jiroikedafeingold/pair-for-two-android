# Releasing Pair for two (Android)

The iOS side of this app has a settled App Store workflow (see the iOS repo's `fastlane/` and the
accounts table in `~/Projects/AppStoreConnect/README.md`). Android has no equivalent yet. This file
records what is in place, what one release actually takes, and — honestly — what still needs a
Google account rather than code.

## Versioning

Same convention as the iOS app, and as StarBattleAndroid:

| Field | Meaning | Rule |
| --- | --- | --- |
| `versionCode` | the build number | **Must increase for every upload.** Bump on every push. |
| `versionName` | the marketing version | Patch bump by default; minor or major when the release earns it. |

Both live in `app/build.gradle.kts`. Settings shows them back to the player at the bottom of the
screen, which is what to ask for when someone reports a bug.

Android's `versionCode` and iOS's build number are **independent counters**. They will drift, and
that is fine — nothing reads across.

## Signing

`app/build.gradle.kts` reads `keystore.properties` from the repo root:

```properties
storeFile=pairfortwo-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Both that file and the `.jks` are **gitignored and must stay that way** — an upload key in a public
repo is an app takeover. A release build with no key present still assembles (unsigned), so the
build doesn't break on a machine that hasn't got it.

**Nothing exists yet.** Generating the upload key is the first step of the first real release:

```bash
keytool -genkey -v -keystore pairfortwo-release.jks -alias upload \
        -keyalg RSA -keysize 2048 -validity 10000
```

Keep it somewhere it will survive this laptop. With Play App Signing, losing the *upload* key is
recoverable through Google; losing it before enrolling is not.

## Building

```bash
./gradlew :core:test :app:testDebugUnitTest    # everything that can fail cheaply
./gradlew :app:bundleRelease                   # the AAB the Play Console wants
```

The output lands in `app/build/outputs/bundle/release/`.

## Art

`python3 tools/generate-icon.py` regenerates every launcher icon and the 512px store icon from the
**iOS** `icon_1024.png`, so the two stores show the same art. Don't hand-edit the generated files.
The splash is that icon on felt, via `core-splashscreen` (`Theme.PairForTwo.Splash`).

## What is still missing

None of this is code, and all of it needs your Google account:

1. **A Play Console developer account** and an app entry for `com.jirofeingold.pairfortwo`.
2. **Store listing** — title, short and full description, feature graphic (1024×500), phone and
   tablet screenshots. The iOS `fastlane/metadata` copy is a starting point, minus the Game Center
   and Bluetooth claims, which are not true of this build (PLAN.md §0).
3. **A privacy policy URL.** The app collects nothing and talks only to the other device over the
   local network, but Play requires the declaration regardless. The iOS app already publishes one
   through GitHub Pages (`docs/` in the iOS repo) — it can serve both.
4. **A data safety form**, saying the same: no collection, no sharing.
5. **`fastlane supply`**, if this should be as scripted as the iOS side. `fastlane/metadata/android/`
   already exists with the store icon in it, so the layout is started.

## Before the first upload

Two things in this repo are still unproven, and both want real hardware rather than an emulator:

- **A real phone-to-phone game over Wi-Fi.** `NsdManager`, the multicast lock and AP isolation
  cannot be exercised by the emulator or by the desktop interop harness (PLAN.md §10).
- **The full manual matrix in PLAN.md §9** — both hosting roles, backgrounding, a Wi-Fi drop, a
  force-quit and rejoin, quit propagation, and all three scoring modes, including against an iPhone.
