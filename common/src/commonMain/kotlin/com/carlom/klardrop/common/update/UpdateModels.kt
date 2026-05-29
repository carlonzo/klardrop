package com.carlom.klardrop.common.update

import kotlinx.serialization.Serializable

/**
 * How this running copy of Klardrop was installed. Drives the exact upgrade
 * instruction we show the user. Detected at runtime on desktop; always
 * [UNKNOWN] on mobile (those platforms update through their app stores).
 */
enum class InstallChannel {
  AUR,       // Arch: yay -S klardrop-bin
  APT,       // Debian/Ubuntu: apt upgrade
  FLATPAK,   // flatpak update com.carlom.Klardrop
  BREW,      // macOS Homebrew cask
  DMG,       // macOS: downloaded .dmg by hand
  TARBALL,   // Linux: extracted the universal tarball under /opt by hand
  MANUAL,    // installed some other way
  UNKNOWN,   // couldn't tell / not a desktop build
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

/** Result of an update check. The UI shows a banner only for [Available]. */
sealed interface UpdateStatus {
  /** Not checked yet, check in flight, or check failed — render nothing. */
  data object Unknown : UpdateStatus

  /** Checked and we're on the latest version — render nothing. */
  data object UpToDate : UpdateStatus

  /** A newer version exists; [action] is the channel-specific way to get it. */
  data class Available(
    val version: String,
    val channel: InstallChannel,
    val action: UpdateAction,
  ) : UpdateStatus
}

/** What the "update" button does, depending on the install channel. */
sealed interface UpdateAction {
  /** A shell command the user runs to upgrade (the banner offers to copy it). */
  data class RunCommand(val command: String) : UpdateAction

  /** A URL to open in the browser (download page / specific asset). */
  data class OpenUrl(val url: String) : UpdateAction
}
