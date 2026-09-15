# Linux system JRE packaging (coding-agent brief)

Repo worktree: `/home/carlo/Projects/klardrop-linux-system-jre`
Branch: `pack/linux-system-jre` (already created from `origin/main` @ `5bc68b3d`). **Work only here.**

Carlo asked for a **PR**. Commit on this branch, push, `gh pr create` against `main`. Do **not** merge. Do **not** touch `/home/carlo/Projects/klardrop` (dirty `fix/transfer-intent-ux` + Graal spike — leave it). Do **not** add Graal/Native Image files.

## Product decision

Compose **desktop** is Linux + Windows only. Mac is the native app — do not change Homebrew/DMG.

- **Windows:** keep `jpackage` + bundled JRE (MSI). Users do not have Java.
- **Linux:** **do not ship a JRE.** Jars + a `bin/klardrop` wrapper that execs system `java`. Package managers declare the runtime:
  - AUR `klardrop-bin`: `depends=('java-runtime>=21' 'hicolor-icon-theme')`
  - `install.sh` (no Depends): preflight `java` ≥ 21, die with pacman/apt/dnf install hints
- Bytecode floor is **21** (`jvmTarget = JVM_21` in `desktop/build.gradle.kts`). 22–26 are fine. 17 is not.

This is packaging. Do **not** rewrite UKEY2, Compose UI, or Linux actuals. Do **not** strip sqlite-jdbc / Skiko in this PR (separate size cuts).

## Why jpackage cannot just “omit the JRE”

`compose.desktop.nativeDistributions` always `jlink`s a runtime into the app-image. Linux must **stop using that image as the shipped payload**. CI may still run `:desktop:createReleaseDistributable` so the existing xvfb ProGuard smoke test (`Starting Klardrop`, no VerifyError) keeps working.

Shipped Linux tarball is **not** that full app-image.

## Target tarball layout (keep the names the updater already expects)

```
klardrop-linux-x64/
  klardrop/
    bin/klardrop          # shell wrapper, not the jpackage native launcher
    lib/app/              # ProGuard’d jars + libskiko-linux-x64.so
                          # NO lib/runtime/
  klardrop.desktop
  com.carlom.Klardrop.metainfo.xml
  icons/…
```

Install paths stay `/opt/klardrop` and `~/.local/lib/klardrop`. `install.sh` still copies `src/klardrop` → `$APP_DIR` and links `$APP_DIR/bin/klardrop`.

### Wrapper (`klardrop/bin/klardrop`)

- Resolve its own path; `cd` to the app root.
- Find `java` on `PATH` (or `JAVA_HOME/bin/java`).
- Parse `java -version` → major ≥ 21 or die with a short distro hint.
- `exec` with the same `jvmArgs` as `desktop/build.gradle.kts` (`-Xms24m`, `-Xmx192m`, G1 flags, …).
- Classpath = every jar in `lib/app`. Skiko `.so` already sits there in today’s jpackage layout — keep that.
- Main class: `MainKt`.
- **Must** export something the JVM can see as the *script* path, not the `java` binary:
  - `-Dklardrop.launcher=<absolute path to this script>` and/or `KLARDROP_LAUNCHER=…`
  - Then change `currentLauncherPath()` in `common/src/desktopJvmMain/kotlin/com/carlom/klardrop/common/update/UpdatePlatform.desktopJvm.kt`.

**This is load-bearing.** Today `ProcessHandle.current().info().command()` is the jpackage native launcher (or bundled java) **inside** `/opt/klardrop` / `~/.local/lib/klardrop`. After this change, `command()` is `/usr/bin/java`. Without the property/env:

- `linuxInstallRoot` returns null → tarball self-update dies
- `pacman -Qo /usr/bin/java` → `jre-openjdk`, not klardrop → channel detection lies

Prefer `-Dklardrop.launcher` over `ProcessHandle` when set. Fall back to today’s command path for Windows MSI.

`DesktopTarballInstaller.relaunch` stays `appDir/bin/klardrop` (the script). Staging still uses `klardrop-linux-x64/klardrop` as the app-image.

## CI / artifacts

Files:

- `.github/workflows/release.yml` — “Stage Linux universal tarball” (~line 280): copy **jars + skiko + wrapper**, not `lib/runtime`. Do **not** attach Linux jpackage `.deb`/`.rpm` (those still embed a JRE). Windows MSI unchanged.
- `.github/workflows/release-nightly.yml` — same tarball staging.
- Keep the xvfb minified-app smoke test on the jpackage launcher if that is still the easiest ProGuard gate. Optionally also run the new wrapper against the runner’s JDK (21+).

If `find … *.deb *.rpm` on the linux matrix would still pick fat packages, stop copying them on that matrix (Windows job still copies `.msi`).

## install.sh

- Header comment: no longer “bundled JRE — no Java needed”.
- After arch check: require `java` major ≥ 21. On failure, print:
  - Arch/Omarchy: `pacman -S jre-openjdk`
  - Debian/Ubuntu: `apt install openjdk-21-jre` (or newer)
  - Fedora: `dnf install java-21-openjdk`
- Layout check can stay `[ -d "$src/klardrop/bin" ]` plus `[ -f "$src/klardrop/bin/klardrop" ]`. Do **not** require `lib/runtime`.

## AUR

`packaging/aur/PKGBUILD`:

- `depends=('java-runtime>=21' 'hicolor-icon-theme')`
- Rewrite the comment that says there is no Java dependency because of jpackage.
- Layout can stay `/opt/klardrop` + `/usr/bin/klardrop` symlink. `options=('!strip')` is still fine.

## Docs

Update `packaging/README.md`: Linux tarball has no JRE; AUR pulls `java-runtime>=21`; Windows still bundles; Mac native unchanged.

## Tests (must exist, must fail before the launcher-path fix if you do TDD)

- `currentLauncherPath` / channel detection: when `-Dklardrop.launcher=/home/x/.local/lib/klardrop/bin/klardrop`, TARBALL/PACMAN logic still sees that path, **not** `/usr/bin/java`.
- If that’s hard to unit without extracting helpers, extract a pure function and test it. Do not leave this untested — it is the regression that bricks updates.

Wrapper: a tiny shell test or a documented `bash -n` + fake `java` is enough; don’t stand up Compose.

## Out of scope

- GraalVM, Kotlin/Native `linuxX64`, sqlite-jdbc fat-jar strip, Skiko
- Changing Windows MSI
- Mac Homebrew / DMG / native macOS app
- Merging the PR
- Committing anything in `/home/carlo/Projects/klardrop`

## Git / PR

```bash
# already on pack/linux-system-jre in this worktree
git add …   # only files for this change, including this brief if you want it in the PR
git commit -m "pack(linux): use system JRE instead of bundling jlink"

git push -u origin pack/linux-system-jre
gh pr create --base main --title "pack(linux): ship without a bundled JRE" --body "…"
```

PR body: Linux install size drops the ~89MB jlink runtime; AUR `java-runtime>=21`; Windows still bundles; launcher-path fix for self-update. Print the PR URL at the end.
