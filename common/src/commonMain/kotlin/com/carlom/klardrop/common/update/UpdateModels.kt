package com.carlom.klardrop.common.update

import kotlinx.serialization.Serializable

/**
 * How this running copy of Klardrop was installed. Drives the exact upgrade
 * instruction we show the user. Detected at runtime on desktop; always
 * [UNKNOWN] on mobile (those platforms update through their app stores).
 */
enum class InstallChannel(val displayName: String) {
  BREW("Homebrew"),          // macOS Homebrew cask
  DMG("macOS disk image"),   // macOS: downloaded .dmg by hand
  TARBALL("install.sh"),     // Linux: install.sh script (self-updatable in place)
  DEB("apt / dpkg"),         // Linux: the .deb, installed by dpkg/apt
  RPM("rpm"),                // Linux: the .rpm, installed by rpm/dnf/zypper
  PACMAN("AUR"),             // Arch: pacman-owned, i.e. the klardrop-bin AUR package
  FLATPAK("Flatpak"),        // Linux: running inside a Flatpak sandbox
  SNAP("Snap"),              // Linux: running from a snap mount
  APPIMAGE("AppImage"),      // Linux: launched from an AppImage
  NIX("Nix"),                // Linux: /nix/store-resident build
  MSI("Windows installer"),  // Windows: the .msi
  MANUAL("manual install"),  // installed some other way
  UNKNOWN("unknown"),        // couldn't tell / not a desktop build
}

/**
 * The `latest.json` published next to each GitHub release (also served from
 * `releases/latest/download/latest.json`, which always resolves to the newest
 * non-prerelease). This is the runtime source of truth for "is there an update".
 *
 * Parsed with an explicit serializer ([serializer]) on the platform side so a
 * missing ProGuard keep rule fails at build/keep time rather than silently at
 * runtime in a minified release build.
 */
@Serializable
data class LatestManifest(
  val version: String,
  val tag: String? = null,
  val publishedAt: String? = null,
  val notes: String? = null,
  val platforms: Map<String, ReleaseAsset> = emptyMap(),
)

@Serializable
data class ReleaseAsset(
  val url: String,
  val sha256: String? = null,
  val size: Long? = null,
)

/**
 * Result of an update check. The banner shows only [Available]; the settings
 * sheet renders every state so a manual "Check for updates" has visible feedback.
 *
 * A re-check never regresses [Available] back to [Checking] or [Failed] — once we
 * know a newer version exists, a later flaky fetch must not hide the banner.
 */
sealed interface UpdateStatus {
  /** Never checked (or unsupported platform) — the banner renders nothing. */
  data object Unknown : UpdateStatus

  /** A check is in flight. Only reached from [Unknown], [UpToDate] or [Failed]. */
  data object Checking : UpdateStatus

  /** Checked and we're on the latest version — the banner renders nothing. */
  data object UpToDate : UpdateStatus

  /** The check itself failed (offline, GitHub down, malformed manifest). */
  data class Failed(val message: String) : UpdateStatus

  /** A newer version exists; [action] is the channel-specific way to get it. */
  data class Available(
    val version: String,
    val channel: InstallChannel,
    val action: UpdateAction,
    /** Release-notes page for [version], for the "What's new" link. */
    val notesUrl: String? = null,
  ) : UpdateStatus
}

/** What the "update" button does, depending on the install channel. */
sealed interface UpdateAction {
  /** A shell command the user runs to upgrade (the banner offers to copy it). */
  data class RunCommand(val command: String) : UpdateAction

  /** A URL to open in the browser (download page / specific asset). */
  data class OpenUrl(val url: String) : UpdateAction
}

/**
 * Progress of an in-app self-update (download + verify + stage), for channels
 * that can replace their own files (the Linux script install). The checker drives
 * this through [UpdateChecker.install]; the banner renders it. Channels that can't
 * self-install never leave [Idle] and fall back to the [UpdateAction].
 */
sealed interface InstallProgress {
  /** No self-update in flight (or the channel can't self-install). */
  data object Idle : InstallProgress

  /** Downloading the new build. [fraction] is 0..1, or null when indeterminate (e.g. extracting). */
  data class Downloading(val fraction: Float?) : InstallProgress

  /** Downloaded, verified and staged — a restart applies it. */
  data object Ready : InstallProgress

  /** The self-update failed; the banner falls back to the [UpdateAction]. */
  data class Failed(val message: String) : InstallProgress
}
