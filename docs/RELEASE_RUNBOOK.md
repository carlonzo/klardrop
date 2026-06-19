# Klardrop 1.0.0 Release Runbook

Status of every distribution channel for the first public release, what's wired
up, and the concrete steps left.

**Two workflows now:**
- `.github/workflows/release.yml` — **production**, driven by the git tag `vX.Y.Z`.
  Ships GitHub Release (Linux/Win/Mac), Play **production**, Homebrew.
- `.github/workflows/release-nightly.yml` — **tester tracks**, on a daily schedule +
  manual dispatch. Ships iOS **TestFlight**, Android Play **internal** track, and a
  rolling GitHub **prerelease** (Mac DMG + Linux tarball). Aborts when no commits
  landed since the last nightly. After every stable release, `release.yml` auto-
  dispatches it so testers are never behind production.

## TL;DR — what blocks 1.0.0

| Channel | Built? | Published? | Blocker |
|---------|--------|------------|---------|
| Linux (tarball + .deb) prod | ✅ | ✅ | none — ready |
| Windows (.msi) prod | ✅ | ✅ | none — ready |
| Android prod → Play production | ✅ | ✅ job wired | Play API + first manual upload |
| Android nightly → Play internal | ✅ | ✅ job wired | Play API |
| macOS (.dmg + Homebrew) prod | ✅ | ⚠️ gated off | Apple Developer ID ($99) |
| macOS/Linux nightly prerelease | ✅ | ✅ job wired | (Mac DMG unsigned until Apple ID) |
| iOS / iPad → TestFlight (nightly) | ✅ job wired | ❌ | Apple Developer + distribution cert |

\* Android is debug-signed (and Play upload skipped) unless the keystore secrets are set.

The CI is now complete — the only blockers are the **manual external setup**:
**(1) Apple Developer Program enrollment** (unblocks macOS notarization + iOS TestFlight)
and **(2) the Google Play API connection** + first manual upload (unblocks Play). All
the rest is secrets + console forms; see the per-section steps and the final checklist.

### Version scheme (one identifier — the commit count — for stable and nightly)

Everything derives from `COMMITS = git rev-list --count HEAD`, so nightly and stable
stay consistent (no dates anywhere):

- **`versionCode`** = `COMMITS × 2` for stable, `COMMITS × 2 + 1` for nightly. A single
  increasing integer where **higher = newer commit**; stable is even, the nightly
  rebuilt from the same commit by `refresh-testers` is odd (one higher, so it supersedes
  the prior nightly on the tester tracks) — they never collide. This is the authoritative
  ordering on every store.
- **Stable `versionName`** = the semver tag, e.g. `1.0.0`.
- **Nightly `semver`** = `<base>-nightly.<COMMITS>` where `<base>` is the **next patch
  above the latest stable tag** (`1.0.0` before the first release; `1.0.1` after `v1.0.0`,
  …). Per semver this sorts **above the current stable and below the next release**
  (`1.0.0 < 1.0.1-nightly.717 < 1.0.1`), so reading it tells you exactly where it sits.
  Used for **Android `versionName`** and the **GitHub release title** (free-form strings).
- **Nightly numeric `base`** (e.g. `1.0.1`) — used for **iOS `CFBundleShortVersionString`**
  and **desktop `packageVersion`**, because those fields must be plain numeric `X.Y.Z`
  (a `-nightly` suffix is rejected by App Store Connect and jpackage). The build number
  (`versionCode`) carries the ordering there. TestFlight accepts this — `1.0.1` is a valid
  short version and each upload's build number increases.

Caveat: `versionCode` starts ~1434 for v1.0.0 — fine for a first release, but **don't**
manually upload anything with a higher code to Play/TestFlight first, or the workflow's
lower codes get rejected.

**How each platform consumes it** (all CI-driven, no hand-editing per release):
- **Android** — `versionName` ← `-Pklardrop.version` (stable tag / nightly semver);
  `versionCode` ← `-Pklardrop.versionCode`. `applicationId = com.carlom.klardrop`.
- **Desktop** — `packageVersion` ← `-Pklardrop.version` (stable tag / nightly base).
- **iOS / macOS** — `MARKETING_VERSION` (stable tag / nightly base) + `CURRENT_PROJECT_VERSION`
  (versionCode) on the `xcodebuild` command line. **This was previously broken:** all four
  `Info.plist`s hard-coded `1.0`/`1` with `GENERATE_INFOPLIST_FILE = NO`, so every build
  shipped as `1.0`/`1` (a second TestFlight upload would be rejected for a duplicate build
  number). Now fixed — the plists reference `$(MARKETING_VERSION)`/`$(CURRENT_PROJECT_VERSION)`,
  the xcconfig provides local-build defaults, and every shipping `xcodebuild archive`
  (iOS + KlardropMac, both workflows) passes the real values. The command-line override
  applies to the app + share-extension targets together, so their versions always match
  (App Store requires that).

---

## 1. Linux — ✅ done

Fully wired, no action needed.

- `:desktop:packageReleaseDeb` → `.deb`, `createReleaseDistributable` → app-image
  staged into `klardrop-linux-x64.tar.gz`.
- `packaging/install.sh` (`curl … | bash`) installs the tarball, verifies sha256,
  auto-detects scope (`~/.local` vs `/opt`).
- **Auto-update works**: desktop app reads `latest.json`, user-scope installs
  self-update + restart; root installs get the reinstall one-liner.
- ProGuard smoke-test gate boots the minified app-image before publishing.

Nothing to configure. The `install.sh` URL is served from `raw.githubusercontent.com`
once `main` has the script (already there).

## 2. Windows — ✅ done

- `:desktop:packageReleaseMsi` → `.msi`, listed in `latest.json`.
- Auto-update = in-app **Download** button to the new `.msi` (no silent update).
  Acceptable for 1.0; revisit with an installer-based updater later if wanted.

No action needed.

## 3. Android — jobs wired, needs the Play API + first manual upload

### What works (CI is done)
- `:android:assembleRelease` (APK on the GitHub Release) and `:android:bundleRelease`
  (AAB). Signing reads `KLARDROP_KEYSTORE_*` env from secrets `ANDROID_KEYSTORE_BASE64`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
  **Missing secrets → debug-signed + Play upload skipped.**
- **`play-publish` job** in `release.yml` → Play **production** track (stable tags only).
- **`android` job** in `release-nightly.yml` → Play **internal** track.
- `applicationId = com.carlom.klardrop` (matches the registered Play listing; the
  `namespace` stays `com.carlom.klardrop.android` — source package, independent of the
  install id). versionName/versionCode per the scheme above.

### Action items (manual, external)
1. **Verify the four `ANDROID_*` GitHub secrets are set** (repo → Settings → Secrets →
   Actions). Log prints "AAB will be debug-signed" + skips Play upload if absent.
2. **Set up Play App Signing + create the app listing.** Do the very first upload by
   hand (any signed AAB) to create the app, enrol App Signing (Google holds the app
   key, you keep the `ANDROID_KEYSTORE_*` upload key), and complete **store listing,
   content rating, data-safety, target-audience** — Play rejects API uploads until
   these are done.
3. **Connect the Play Developer API** ("account ready but not connected"):
   - Play Console → Setup → API access → link/create a **Google Cloud project**.
   - Create a **service account**, grant *Service Account User*, download its **JSON key**.
   - Play Console → Users & permissions → invite the service-account email, grant
     **Release** to the testing + production tracks. Wait a few minutes to propagate.
   - Add the JSON as GitHub secret **`PLAY_SERVICE_ACCOUNT_JSON`**.

That's it — the `play-publish` (production) and nightly `android` (internal) jobs then
upload automatically.

### Store listing prerequisites (one-time, manual in Console)
App name, short/full description, feature graphic, phone + tablet screenshots,
app icon (512px), privacy policy URL, content rating questionnaire, data-safety
form, target audience. These gate the *first* production release regardless of API.

## 4. macOS — built unsigned, every channel gated off until Apple signing

### Current behaviour (no Apple secrets)
`build-macos-native` archives `KlardropMac` (Swift/SKIE native app), builds a `.dmg`,
uploads it as a **CI artifact only**. Without the signing secrets it does **not**:
- write `macos-verified.txt`, so `latest.json` omits macOS entirely, **and**
- the `homebrew` job is gated on `macos-verified.txt` and **skips** — so the cask
  is never updated.

> ⚠️ `packaging/README.md` "macOS signing" section is **stale**: it claims the cask
> ships an unsigned DMG and strips quarantine. The cask + workflow were since changed
> to **require** a notarized DMG. Net result today: **macOS has no working install
> channel at all** until Apple signing is set up. Fix the README when you enroll.

### Action items (after Apple Developer enrollment, §5)
The workflow is already written — it just needs the secrets. Provide all of:

| Secret | What |
|--------|------|
| `MACOS_CERTIFICATE_P12_BASE64` | "Developer ID Application" cert + key, exported as `.p12`, base64'd |
| `MACOS_CERTIFICATE_PASSWORD` | password for that `.p12` |
| `APPLE_TEAM_ID` | 10-char team ID |
| `APPSTORE_API_KEY_ID` | App Store Connect API key ID |
| `APPSTORE_API_ISSUER_ID` | API issuer ID |
| `APPSTORE_API_KEY_P8_BASE64` | the `AuthKey_*.p8`, base64'd |

Once present, the job signs (Developer ID), notarizes via `notarytool`, staples,
writes `macos-verified.txt` → `latest.json` lists macOS → the `homebrew` job renders
and pushes the cask. `brew install --cask carlonzo/klardrop/klardrop` then works.

### macOS auto-update
The native Swift app has **no in-app updater** (Sparkle/appcast not present; the
`latest.json` self-updater lives in the desktop/JVM app only). macOS users update
via **`brew upgrade --cask klardrop`**. The Homebrew job auto-bumps the cask on every
stable release, so this is the update path. Adding Sparkle is a post-1.0 nice-to-have.

## 5. Apple Developer enrollment (the gate for macOS + iOS)

This is the $99/yr program you haven't done yet. Order of operations:

1. **Enroll** at developer.apple.com ($99/yr). Individual or organization.
2. **Create certificates** (Certificates, IDs & Profiles):
   - *Developer ID Application* → for the notarized macOS `.dmg` (direct download + Homebrew).
   - *Apple Distribution* → for the App Store / TestFlight builds (iOS + Mac App Store if wanted).
3. **Register App IDs / bundle IDs**: `com.carlom.Klardrop` (main), plus the share
   extensions `com.carlom.Klardrop.Share` (iOS) and `com.carlom.Klardrop.MacShare`.
   The widget/extension IDs already exist in the pbxproj.
4. **App Store Connect API key** (Users & Access → Integrations → App Store Connect
   API): create a key with *App Manager* role → download the `.p8`, note key ID +
   issuer ID. Used for both notarization and TestFlight upload.
5. **Team ID is `D7T5425WSW`** — already hardcoded as `DEVELOPMENT_TEAM` on every
   target. Leave `TEAM_ID` **empty** in `iosApp/Configuration/Config.xcconfig`: the iOS
   app's bundle ID is now hardcoded bare (`com.carlom.Klardrop`) so it matches the
   registered App ID. (It used to be `${BUNDLE_ID}${TEAM_ID}`; filling `TEAM_ID` would
   suffix the bundle ID and App Store Connect would reject the archive — so don't.)
6. ✅ **`iosApp` scheme is shared** (`iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme`),
   so the nightly `-scheme iosApp` archive finds it.
7. **Add the secrets to GitHub** — the six in §4 **plus** the iOS distribution cert
   (the `MACOS_CERTIFICATE_*` Developer-ID cert is for the Mac DMG only; iOS/TestFlight
   needs an *Apple Distribution* cert):

   | Secret | What |
   |--------|------|
   | `APPLE_DISTRIBUTION_P12_BASE64` | "Apple Distribution" cert + key, exported as `.p12`, base64'd |
   | `APPLE_DISTRIBUTION_P12_PASSWORD` | password for that `.p12` |

   The nightly iOS job uses automatic provisioning (`-allowProvisioningUpdates` + the
   App Store Connect API key), so Xcode creates the App Store provisioning profiles
   for the app + share extension on the fly — no profiles to commit.

### 5b. Entitlements — already declared, just register the App IDs

All entitlements files exist and are correct in the repo. You do **not** need to
write any; you only need to **enable the matching capability on each App ID** in the
Developer portal (and register the App Group), or signing fails with a profile
mismatch. Hardened Runtime is already `ENABLE_HARDENED_RUNTIME = YES` on the Mac
targets (required for notarization) — nothing to change.

| Target | Bundle ID | Entitlements declared | Portal capability to enable |
|--------|-----------|----------------------|------------------------------|
| iOS app | `com.carlom.Klardrop` | App Groups | **App Groups** |
| iOS share ext | `com.carlom.Klardrop.Share` | App Groups | **App Groups** |
| macOS app | `com.carlom.Klardrop` | App Sandbox, network client+server, user-selected + downloads files RW, Bluetooth, App Groups | **App Groups** (sandbox/network/files/BT are baked into the `.entitlements`, not portal toggles) |
| macOS share ext | `com.carlom.Klardrop.MacShare` | App Sandbox, user-selected read-only, App Groups | **App Groups** |

Concrete portal steps:
1. **Register the App Group** `group.com.carlom.Klardrop` (Identifiers → App Groups).
2. For each of the 4 App IDs, tick **App Groups** and assign it to that group.
3. ⚠️ **App Group team-prefix gotcha:** the Mac entitlements hard-code
   `D7T5425WSW.group.com.carlom.Klardrop` (team prefix `D7T5425WSW`), while the iOS
   ones use the unprefixed `group.com.carlom.Klardrop`. **`D7T5425WSW` must equal your
   real Team ID** once enrolled. If your team ID differs, update the two Mac
   entitlements files (`KlardropMac.entitlements`, `KlardropMacShare.entitlements`) to
   your prefix — otherwise the main app and share extension can't share the group
   container and incoming-share handoff breaks.

**Privacy usage strings** (already in `Info.plist` / `MacInfo.plist`, no action, but
these are what App Review reads — make sure they stay accurate):
`NSLocalNetworkUsageDescription`, `NSBonjourServices` (`_klardrop._tcp.`,
`_FC9F5ED42C8A._tcp.`), `NSBluetoothAlwaysUsageDescription`,
`NSPhotoLibraryUsageDescription`, `NSPhotoLibraryAddUsageDescription`.

### 5c. Auto-upload to App Store Connect / TestFlight (the API)

One App Store Connect API key (§5 step 4) drives **both** macOS notarization and iOS
TestFlight upload — no Apple ID password / app-specific password needed, and it's
already wired in CI:

- **macOS** (`release.yml` + nightly `macos` job): `notarytool submit --key … --key-id
  … --issuer …`.
- **iOS** (nightly `ios` job): `xcodebuild archive` then `xcodebuild -exportArchive`
  with `method=app-store-connect` + `destination=upload` and the `-authenticationKey*`
  API-key flags — the export step uploads straight to TestFlight, no separate
  `altool`/Transporter step.

The build appears in TestFlight after Apple's processing (~5–15 min). **One manual
thing left:** the first TestFlight build needs **export-compliance** answered. Set
`ITSAppUsesNonExemptEncryption = false` in the iOS `Info.plist` (Klardrop is
local-network only) to skip that prompt on every build — it isn't set yet.

## 6. iOS / iPad — TestFlight job wired (in the nightly workflow)

iOS ships to testers via the **`ios` job in `release-nightly.yml`** (TestFlight only —
no production App Store path yet, by design). It mirrors `build-macos-native`'s prep
(JDK 21 → `generateDummyFramework` → `pod install`), archives `-scheme iosApp` with
automatic provisioning (`-allowProvisioningUpdates` + the API key), and uploads via the
export step in §5c. iOS pulls Bugsnag through CocoaPods directly, so no synthetic
framework prebuild is needed (unlike macOS). CI is done; the remaining work is the
manual Apple setup in §5 (enrollment, App IDs + App Group, distribution cert, `TEAM_ID`,
shared scheme) and creating the App Store Connect app record (`com.carlom.Klardrop`).

## 7. Nightly / tester tracks — ✅ implemented (`release-nightly.yml`)

Runs daily (`cron: 0 4 * * *`) and on manual dispatch. Pipeline:

- **`gate`** — aborts the whole run unless commits landed since the last nightly
  (tracked by a moving `nightly` git tag); manual dispatch with `force: true` overrides.
  Derives the version (UTC date + the `commits*2+1` versionCode).
- **`android`** → Play **internal** track. **`ios`** → **TestFlight**.
  **`linux`** + **`macos`** → build artifacts.
- **`publish`** — collects the desktop artifacts into a rolling GitHub **prerelease**
  (tag `nightly`: Mac DMG + Linux tarball) and advances the `nightly` tag to this
  commit. `prerelease: true` keeps it off `releases/latest`, so `install.sh` and
  Homebrew (which track the stable release) are untouched. Runs only when every build
  job succeeded, so a failed nightly leaves the tag in place and retries next run.

**Testers never lag production:** `release.yml`'s `refresh-testers` job dispatches this
workflow (`force: true`, against the release tag) after each stable release, so the
released commit also lands on Play internal + TestFlight + the nightly prerelease with a
fresh, higher versionCode. (Requires `release-nightly.yml` to exist on the tagged commit
— it will, since it's committed before the first tag.)

Same secrets as production — no extra config beyond §3 (Play) and §5 (Apple). Adjust the
`cron` if daily is too frequent.

---

## Release-day checklist for 1.0.0

Pre-reqs: Apple enrolled + secrets set (§4 + §5), Play API connected +
`PLAY_SERVICE_ACCOUNT_JSON` set, Android keystore secrets set, store listings filled.

**Manual setup (you):**
1. [ ] `ANDROID_*` secrets set (else AAB is debug-signed + Play upload skipped).
2. [ ] First AAB uploaded by hand → Play App Signing enrolled, store listing + content
       rating + data-safety complete.
3. [ ] Play Developer API connected → `PLAY_SERVICE_ACCOUNT_JSON` set.
4. [ ] Apple enrolled; Developer ID + Apple Distribution certs created.
5. [ ] Secrets set: `MACOS_CERTIFICATE_P12_BASE64`/`_PASSWORD`, `APPLE_DISTRIBUTION_P12_BASE64`/`_PASSWORD`,
       `APPLE_TEAM_ID`, `APPSTORE_API_KEY_ID`/`_ISSUER_ID`/`_P8_BASE64`.
6. [ ] App IDs (`com.carlom.Klardrop`, `.Share`, `.MacShare`) + App Group
       `group.com.carlom.Klardrop` registered with **App Groups** capability.
7. [ ] `TEAM_ID` filled in `Config.xcconfig`; Mac entitlements team-prefix matches;
       Release bundle ID verified as `com.carlom.Klardrop`.
8. [ ] `iosApp` scheme shared + committed.
9. [ ] `ITSAppUsesNonExemptEncryption = false` added to the iOS `Info.plist`.
10. [ ] App Store Connect iOS app record created.
11. [ ] `packaging/README.md` macOS-signing section updated (it's stale — no longer unsigned).

**Release:**
12. [ ] Tag `v1.0.0` (or run the Release workflow with `1.0.0`).
13. [ ] GitHub Release has `.dmg` (+`macos-verified.txt`), `.msi`, `.deb`, `.tar.gz`,
        `.apk`, `.sha256` sidecars, and `latest.json` lists **all** platforms.
14. [ ] Homebrew cask bumped; `brew install --cask carlonzo/klardrop/klardrop` works.
15. [ ] Play **production** release live (from `play-publish`).
16. [ ] `refresh-testers` dispatched the nightly → build on Play **internal** +
        **TestFlight** + the `nightly` prerelease.
17. [ ] Smoke-test `install.sh` on a clean Linux box + in-app update from a prior build.
