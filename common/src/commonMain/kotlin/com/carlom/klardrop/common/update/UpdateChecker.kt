package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Checks for a newer Klardrop release and exposes a [status] flow the UI can
 * render — as a banner on the discovery screen, and as the Updates section of the
 * settings sheet. Desktop-only in practice: on mobile [fetcher] is null, [supported]
 * is false and the checker stays [UpdateStatus.Unknown] forever.
 *
 * [start] polls in the background so a long-running desktop session notices a
 * release published after launch; [checkNow] is the manual "Check for updates".
 *
 * @param currentVersion this build's version (KlardropVersion.VERSION).
 * @param osType used to pick the right download asset for the open-URL fallback.
 * @param fetcher platform manifest fetcher, or null where unsupported.
 * @param detectChannel resolves the install channel (runs subprocesses; called off-main).
 * @param installerFactory builds the in-app self-updater for a channel, or returns
 *   null when self-install isn't possible (then the UI uses the [UpdateAction]).
 * @param releaseChannel "stable" or "nightly" — the update track this build follows.
 * @param manifestUrl the `latest.json` URL; defaults to the stable "latest release" link.
 * @param recheckInterval how often [start]'s background loop re-checks.
 */
class UpdateChecker(
  private val currentVersion: String,
  private val osType: OsType,
  private val fetcher: UpdateManifestFetcher?,
  private val detectChannel: () -> InstallChannel,
  coroutines: Coroutines,
  private val installerFactory: (InstallChannel) -> UpdateInstaller? = ::createUpdateInstaller,
  private val releaseChannel: String = "stable",
  private val manifestUrl: String = DEFAULT_MANIFEST_URL,
  private val recheckInterval: Duration = DEFAULT_RECHECK_INTERVAL,
) {

  private val scope = coroutines.newScope(coroutines.ioDispatcher)

  private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
  val status: StateFlow<UpdateStatus> = _status.asStateFlow()

  private val _install = MutableStateFlow<InstallProgress>(InstallProgress.Idle)
  /** Progress of the in-app self-update, when one is possible. See [InstallProgress]. */
  val install: StateFlow<InstallProgress> = _install.asStateFlow()

  /** This build's version, for the settings sheet. */
  val version: String get() = currentVersion

  /** The update track this build follows ("stable" / "nightly"), for the settings sheet. */
  val channel: String get() = releaseChannel

  /**
   * Whether this platform checks for updates at all. False on Android/iOS, where
   * the store owns updates — the UI hides its whole Updates section there.
   */
  val supported: Boolean get() = fetcher != null

  /** The staged self-updater, kept so [applyUpdate] can swap + relaunch. */
  private var installer: UpdateInstaller? = null

  /** Serializes checks so a manual tap during the periodic check doesn't double-fetch. */
  private val checkLock = Mutex()

  private var periodicJob: Job? = null

  /**
   * Check now, then keep re-checking every [recheckInterval] until [stop]. Idempotent:
   * a second call while the loop is running is a no-op. No-op without a [fetcher].
   */
  fun start() {
    if (fetcher == null || periodicJob?.isActive == true) return
    periodicJob = scope.launch {
      while (isActive) {
        runCheck()
        delay(recheckInterval)
      }
    }
  }

  /** Stop the background re-check loop started by [start]. */
  fun stop() {
    periodicJob?.cancel()
    periodicJob = null
  }

  /**
   * Fetch the manifest and update [status]. No-op on platforms without a fetcher, and
   * a no-op while another check is already in flight. A previously failed self-install
   * is reset so an explicit re-check retries the download.
   */
  fun checkNow() {
    val f = fetcher ?: return
    if (_install.value is InstallProgress.Failed) _install.value = InstallProgress.Idle
    scope.launch { runCheck(f) }
  }

  private suspend fun runCheck(f: UpdateManifestFetcher? = fetcher) {
    if (f == null) return
    // A check already running is as good as this one — don't queue a second fetch.
    if (!checkLock.tryLock()) return
    try {
      // Never regress a known-available update to "checking": the banner would
      // flicker away on every background re-check.
      if (_status.value !is UpdateStatus.Available) _status.value = UpdateStatus.Checking

      val manifest = runCatching { f.fetch(manifestUrl) }
        .onFailure { log("UpdateChecker", "manifest fetch failed", it) }
        .getOrNull()

      if (manifest == null) {
        failed("Couldn't reach the update server")
        return
      }

      if (!isNewerVersion(manifest.version, currentVersion)) {
        _status.value = UpdateStatus.UpToDate
        return
      }

      val channel = runCatching { detectChannel() }.getOrDefault(InstallChannel.UNKNOWN)
      log("UpdateChecker", "update available: $currentVersion -> ${manifest.version} via $channel")
      _status.value = UpdateStatus.Available(
        version = manifest.version,
        channel = channel,
        action = actionFor(channel, manifest),
        notesUrl = manifest.notes ?: RELEASES_PAGE,
      )
      maybeSelfInstall(channel, manifest)
    } finally {
      checkLock.unlock()
    }
  }

  /** Record a failed check, unless we already know an update is waiting. */
  private fun failed(message: String) {
    if (_status.value is UpdateStatus.Available) return
    _status.value = UpdateStatus.Failed(message)
  }

  /**
   * If this channel can self-install and the manifest has a matching asset, start
   * downloading + staging it in the background, reflecting progress in [install].
   * On failure the banner falls back to the channel's [UpdateAction].
   */
  private suspend fun maybeSelfInstall(channel: InstallChannel, manifest: LatestManifest) {
    // Only start from a standing start: a download in flight, an already-staged
    // update, or a failure the user hasn't retried must not be restarted by the
    // background loop.
    if (_install.value != InstallProgress.Idle) return
    val inst = runCatching { installerFactory(channel) }.getOrNull() ?: return
    val asset = manifest.platforms[ASSET_LINUX_TARBALL] ?: return
    installer = inst
    _install.value = InstallProgress.Downloading(null)
    runCatching {
      inst.downloadAndStage(asset) { fraction ->
        _install.value = InstallProgress.Downloading(fraction)
      }
    }.onSuccess {
      log("UpdateChecker", "update staged; ready to restart")
      _install.value = InstallProgress.Ready
    }.onFailure {
      log("UpdateChecker", "self-install failed", it)
      installer = null
      _install.value = InstallProgress.Failed(it.message ?: "update download failed")
    }
  }

  /** Swap in the staged update and relaunch. No-op unless [install] is [InstallProgress.Ready]. */
  fun applyUpdate() {
    if (_install.value != InstallProgress.Ready) return
    installer?.applyAndRestart()
  }

  /**
   * Fallback action when the in-app self-updater isn't available (see [install]) —
   * which is every channel but a user-writable install.sh install.
   *
   * Each system package manager owns its own files, so the only correct instruction
   * is that manager's own upgrade command; writing over its files behind its back
   * would leave the package database lying. Where we ship the asset but no repo
   * (deb, rpm) the command downloads the new package and hands it to the manager.
   * Channels with nothing to run (a bare .dmg, an AppImage, the MSI) get a download link.
   */
  private fun actionFor(channel: InstallChannel, manifest: LatestManifest): UpdateAction = when (channel) {
    InstallChannel.BREW -> UpdateAction.RunCommand("brew upgrade --cask klardrop")

    // Re-running the installer upgrades in place. Nightly installs must stay on the
    // nightly channel, so pass the flag through.
    InstallChannel.TARBALL -> UpdateAction.RunCommand(
      if (releaseChannel == "nightly") "curl -fsSL $INSTALL_SCRIPT_URL | bash -s -- --nightly"
      else "curl -fsSL $INSTALL_SCRIPT_URL | bash"
    )

    // `apt install ./file.deb` (not `dpkg -i`) so apt resolves any dependency the
    // bundled-runtime package still declares, and records the upgrade properly.
    InstallChannel.DEB -> downloadAndRun(manifest, ASSET_LINUX_DEB) { file ->
      "sudo apt install ./$file"
    }

    // -U is upgrade-or-install; works the same under dnf and zypper systems.
    InstallChannel.RPM -> downloadAndRun(manifest, ASSET_LINUX_RPM) { file ->
      "sudo rpm -Uvh ./$file"
    }

    // The AUR package is source-of-truth for pacman installs; an AUR helper rebuilds it.
    InstallChannel.PACMAN -> UpdateAction.RunCommand("yay -S $AUR_PACKAGE")

    InstallChannel.FLATPAK -> UpdateAction.RunCommand("flatpak update $FLATPAK_APP_ID")

    InstallChannel.SNAP -> UpdateAction.RunCommand("sudo snap refresh klardrop")

    InstallChannel.NIX -> UpdateAction.RunCommand("nix profile upgrade klardrop")

    InstallChannel.DMG,
    InstallChannel.APPIMAGE,
    InstallChannel.MSI,
    InstallChannel.MANUAL,
    InstallChannel.UNKNOWN -> UpdateAction.OpenUrl(downloadUrl(manifest))
  }

  /**
   * A "download the package, then hand it to the package manager" command for [assetKey],
   * or a plain download link when this release has no such asset (an older release, or a
   * build where that format didn't publish).
   */
  private fun downloadAndRun(
    manifest: LatestManifest,
    assetKey: String,
    command: (fileName: String) -> String,
  ): UpdateAction {
    val url = manifest.platforms[assetKey]?.url ?: return UpdateAction.OpenUrl(downloadUrl(manifest))
    val fileName = url.substringAfterLast('/')
    return UpdateAction.RunCommand("curl -fsSLO $url && ${command(fileName)}")
  }

  /** Best download link for this OS, falling back to the release notes page. */
  private fun downloadUrl(manifest: LatestManifest): String {
    val p = manifest.platforms
    val asset = when (osType) {
      OsType.APPLE -> p[ASSET_MACOS]
      OsType.WINDOWS -> p[ASSET_WINDOWS]
      OsType.LINUX -> p[ASSET_LINUX_TARBALL] ?: p[ASSET_LINUX_DEB] ?: p[ASSET_LINUX_RPM]
      else -> null
    }
    return asset?.url ?: manifest.notes ?: RELEASES_PAGE
  }

  companion object {
    const val DEFAULT_MANIFEST_URL =
      "https://github.com/carlonzo/klardrop/releases/latest/download/latest.json"
    // The rolling `nightly` prerelease serves its own latest.json from a fixed tag URL
    // (prereleases are excluded from /releases/latest, so the stable URL never sees it).
    const val NIGHTLY_MANIFEST_URL =
      "https://github.com/carlonzo/klardrop/releases/download/nightly/latest.json"
    const val RELEASES_PAGE = "https://github.com/carlonzo/klardrop/releases/latest"

    /** How often [start]'s loop re-checks. Long enough to be invisible, short enough
     * that a machine left running for days still learns about a release the same day. */
    val DEFAULT_RECHECK_INTERVAL: Duration = 6.hours

    /** `latest.json` platform keys, written by the release workflow. */
    const val ASSET_MACOS = "macos"
    const val ASSET_WINDOWS = "windows"
    const val ASSET_LINUX_TARBALL = "linux-tarball"
    const val ASSET_LINUX_DEB = "linux-deb"
    const val ASSET_LINUX_RPM = "linux-rpm"

    /** The AUR package name published by the release workflow. */
    const val AUR_PACKAGE = "klardrop-bin"

    /** Flatpak application id, matching packaging/linux/com.carlom.Klardrop.metainfo.xml. */
    const val FLATPAK_APP_ID = "com.carlom.Klardrop"

    /** The latest.json URL for a build's update channel ("nightly" vs stable default). */
    fun manifestUrlForChannel(channel: String): String =
      if (channel == "nightly") NIGHTLY_MANIFEST_URL else DEFAULT_MANIFEST_URL

    /** One-line Linux installer; re-running it upgrades in place. */
    const val INSTALL_SCRIPT_URL =
      "https://raw.githubusercontent.com/carlonzo/klardrop/main/packaging/install.sh"
  }
}
