# Packaging & distribution

This directory holds everything needed to distribute the Klardrop **desktop** app.
The version is driven entirely by the git tag: pushing `vX.Y.Z` (or running the
`Release` workflow with a version) builds the artifacts and publishes the GitHub
Release. Every download channel points at that release.

## Layout

```
packaging/
  install.sh                            # Linux installer (curl | bash) + in-app self-update source
  linux/
    klardrop.desktop                    # application-menu entry (Exec rewritten at install time)
    com.carlom.Klardrop.metainfo.xml    # AppStream metadata
  homebrew/
    klardrop.rb                         # macOS cask template (@VERSION@ / @SHA256@ rendered by CI)
  aur/
    PKGBUILD                            # Arch klardrop-bin template (@VERSION@ / @SHA256@ rendered by CI)
  README.md
```

## Channels

### Linux — `install.sh` (primary)

A single script, run straight from the terminal:

```sh
curl -fsSL https://raw.githubusercontent.com/carlonzo/klardrop/main/packaging/install.sh | bash
```

It downloads the latest universal tarball, verifies its sha256, and installs the
app-image plus a launcher, menu entry and icons. **Scope is auto-detected:** run as
your user it installs under `~/.local` (no sudo); run as root (`sudo … | bash`) it
installs system-wide under `/opt`. Re-running upgrades in place. Uninstall:

```sh
curl -fsSL https://raw.githubusercontent.com/carlonzo/klardrop/main/packaging/install.sh | bash -s -- --uninstall
```

Install locations (kept in sync with the app's self-updater — see below):

The per-user app-image is in `~/.local/lib`, not `~/.local/share/klardrop`: that
directory is the app's own data root (FileKit `filesDir` — `databases/`,
`properties.preferences_pb`), and the installer replaces its app-image root
wholesale. Installs made before the split are relocated automatically; their
data is left in place.

| Scope | App-image | Launcher | Desktop entry | Icons |
|-------|-----------|----------|---------------|-------|
| user (default) | `~/.local/lib/klardrop` | `~/.local/bin/klardrop` | `~/.local/share/applications` | `~/.local/share/icons/hicolor` |
| root (`sudo`)  | `/opt/klardrop` | `/usr/local/bin/klardrop` | `/usr/share/applications` | `/usr/share/icons/hicolor` |

### macOS — Homebrew cask

`homebrew/klardrop.rb` is a cask template. On a stable release CI renders the
version + DMG checksum and pushes it to the `carlonzo/homebrew-klardrop` tap as
`Casks/klardrop.rb`. The DMG is unsigned, so the cask strips the quarantine flag on
install (see "macOS signing").

```sh
brew install --cask carlonzo/klardrop/klardrop
```

### Linux — native packages

For users who would rather their distro's package manager owned the install:

| Distro | Channel | Install |
|--------|---------|---------|
| Arch (and derivatives) | AUR `klardrop-bin` | `yay -S klardrop-bin` |
| Debian / Ubuntu | `.deb` on the release | `sudo apt install ./klardrop_<version>_amd64.deb` |
| Fedora / openSUSE | `.rpm` on the release | `sudo rpm -Uvh klardrop-<version>.x86_64.rpm` |

The `.deb` and `.rpm` are jpackage output (bundled JRE, `/opt/klardrop` + a launcher on
`PATH`) and are attached to every stable release; there is no apt/dnf repository, so
upgrading means installing the newer package — which is exactly the command the in-app
updater offers. The AUR package repackages the universal tarball into the same layout,
and is bumped by the `aur` job on every stable release.

The install script stays the primary channel because it is distro-independent and is
the only channel that can self-update from inside the app.

### Windows — MSI

Download the `.msi` from the [latest release](https://github.com/carlonzo/klardrop/releases/latest).

## The universal Linux tarball

The release workflow builds a self-contained tarball, `klardrop-linux-x64.tar.gz`,
consumed by both the install script and the in-app self-updater. It bundles its own
JRE (jpackage app-image), so it has no Java dependency. Internal layout:

```
klardrop-linux-x64/
  klardrop/                             # app-image: bin/klardrop launcher + lib/ (jars + runtime/)
  klardrop.desktop
  com.carlom.Klardrop.metainfo.xml
  icons/<size>/klardrop.png             # 32, 64, 128, 256, 512
  icons/scalable/klardrop.svg           # scalable source (theme prefers it)
```

The `.desktop` uses `Icon=klardrop` — a theme *name*, not a path — so it resolves
to whichever `klardrop.*` the installer dropped into `hicolor` (the SVG at any size,
PNGs as fallback). It works the same for a `/opt` or a `~/.local` install.

## In-app updates

On launch — and every 6 hours after that, since a desktop session routinely outlives
several releases — the app fetches `releases/latest/download/latest.json` (or the
nightly manifest, for a nightly build) and compares its semver to the running build.

Three surfaces show the result:

- **The banner** on the discovery screen, when and only when an update exists.
  Dismissible per version.
- **Settings → Updates**, always: the running version, how this copy was installed,
  the live check state, and a **Check for updates** button. This is where a user who
  simply wonders "am I current?" gets an answer.
- **The tray menu**, so an update is visible while the window is hidden — which, for
  a background share target, is most of the time.

What the update button does depends on how this copy was installed. Klardrop detects
that at runtime (`detectInstallChannel`) rather than assuming, because every system
package manager owns its own files: writing over them would leave the package database
lying about what is installed.

| Detected channel | How it's detected | What the app offers |
|------------------|-------------------|---------------------|
| Linux `install.sh`, user-writable | launcher under `~/.local/lib/klardrop` or `/opt/klardrop` | **Self-update**: downloads the tarball, verifies sha256, stages it, then a **Restart** button swaps the app-image and relaunches — no terminal, no sudo |
| Linux `install.sh`, root-owned `/opt` | as above but not writable | the one-line `curl … \| bash` reinstall |
| Debian / Ubuntu | `dpkg-query -S` owns the launcher | `curl -fsSLO <deb> && sudo apt install ./<deb>` |
| Fedora / openSUSE | `rpm -qf` owns the launcher | `curl -fsSLO <rpm> && sudo rpm -Uvh ./<rpm>` |
| Arch | `pacman -Qo` owns the launcher | `yay -S klardrop-bin` |
| Flatpak | `$FLATPAK_ID` / `/.flatpak-info` | `flatpak update com.carlom.Klardrop` |
| Snap | `$SNAP` or a `/snap/…` launcher | `sudo snap refresh klardrop` |
| AppImage | `$APPIMAGE` or a `/tmp/.mount_…` launcher | download link |
| Nix | launcher in `/nix/store` | `nix profile upgrade klardrop` |
| macOS Homebrew | `brew list --cask klardrop` | `brew upgrade --cask klardrop` |
| Unsigned DMG / Windows MSI / unknown | fallthrough | download link for the platform asset |

Flatpak, Snap, AppImage and Nix are detected but not published by this repo — if a
downstream packager ships one, the app gives that channel's own upgrade command
instead of misdirecting the user to a tarball.

Commands are offered as copy-to-clipboard (the app never runs a privileged command
itself). A failed self-update falls back to the channel's command, and an "update
available" state is never hidden by a later failed check.

The keep rules in `desktop/rules.pro` ensure `latest.json` still parses in the
minified release build; the release workflow boots the minified app-image as a gate.

## One-time setup required (maintainer)

| Channel  | Setup | CI secret |
|----------|-------|-----------|
| Homebrew | Create the `carlonzo/homebrew-klardrop` tap repo, and a token that can push to it. | `HOMEBREW_TAP_TOKEN` |
| AUR | Register the `klardrop-bin` package on aur.archlinux.org and add the matching SSH private key. Optionally pin the host key with `AUR_KNOWN_HOSTS` (`ssh-keyscan aur.archlinux.org`, verified against the AUR's published fingerprint); without it the job trusts the key on first use. | `AUR_SSH_PRIVATE_KEY`, `AUR_KNOWN_HOSTS` (optional) |
| install.sh | None — served from `raw.githubusercontent.com` once on `main`. | — |
| .deb / .rpm | None — built by jpackage and attached to the release. | — |

Both the Homebrew and AUR bump jobs are gated on their secret and on the version being
a stable `X.Y.Z`, so an unconfigured channel is skipped, not failed.

## macOS signing

There is no Apple Developer ID yet, so the macOS DMG ships **unsigned**. Gatekeeper
will block it on first launch; users must right-click → Open (or
`xattr -dr com.apple.quarantine /Applications/Klardrop.app`). Adding an Apple
Developer ID later enables notarization in the macOS build job.
