package com.carlom.klardrop.common.update

import kotlinx.serialization.Serializable

/**
 * How this running copy of Klardrop was installed. Drives the exact upgrade
 * instruction we show the user. Detected at runtime on desktop; always
 * [UNKNOWN] on mobile (those platforms update through their app stores).
 */
enum class InstallChannel {
  BREW,      // macOS Homebrew cask
  DMG,       // macOS: downloaded .dmg by hand
  TARBALL,   // Linux: installed via the install.sh script (self-updatable in place)
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
