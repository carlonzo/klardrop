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

| Scope | App-image | Launcher | Desktop entry | Icons |
|-------|-----------|----------|---------------|-------|
| user (default) | `~/.local/share/klardrop` | `~/.local/bin/klardrop` | `~/.local/share/applications` | `~/.local/share/icons/hicolor` |
| root (`sudo`)  | `/opt/klardrop` | `/usr/local/bin/klardrop` | `/usr/share/applications` | `/usr/share/icons/hicolor` |

### macOS — Homebrew cask

`homebrew/klardrop.rb` is a cask template. On a stable release CI renders the
version + DMG checksum and pushes it to the `carlonzo/homebrew-klardrop` tap as
`Casks/klardrop.rb`. The DMG is unsigned, so the cask strips the quarantine flag on
install (see "macOS signing").

```sh
brew install --cask carlonzo/klardrop/klardrop
```

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

On launch the desktop app fetches `releases/latest/download/latest.json` and
compares its semver to the running build. When a newer version exists:

- **Linux user install** — the app downloads the new tarball, verifies its sha256,
  stages it, and shows a **Restart** button. Restarting swaps the app-image in place
  (a detached helper waits for exit, replaces `…/klardrop`, and relaunches) — no
  terminal, no sudo.
- **Linux root (`/opt`) install** — not self-writable, so the banner offers the
  one-line `curl … | bash` reinstall instead.
- **macOS Homebrew** — offers `brew upgrade --cask klardrop`.
- **Unsigned DMG / Windows MSI / unknown** — offers a Download button to the asset.

The keep rules in `desktop/rules.pro` ensure `latest.json` still parses in the
minified release build; the release workflow boots the minified app-image as a gate.

## One-time setup required (maintainer)

| Channel  | Setup | CI secret |
|----------|-------|-----------|
| Homebrew | Create the `carlonzo/homebrew-klardrop` tap repo, and a token that can push to it. | `HOMEBREW_TAP_TOKEN` |
| install.sh | None — served from `raw.githubusercontent.com` once on `main`. | — |

The Homebrew bump job is gated on its token, so an unconfigured tap is skipped, not
failed.

## macOS signing

There is no Apple Developer ID yet, so the macOS DMG ships **unsigned**. Gatekeeper
will block it on first launch; users must right-click → Open (or
`xattr -dr com.apple.quarantine /Applications/Klardrop.app`). Adding an Apple
Developer ID later enables notarization in the macOS build job.
