package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Checks for a newer Klardrop release and exposes a [status] flow the UI can
 * render as an "update available" banner. Desktop-only in practice: on mobile
 * [fetcher] is null and the checker stays [UpdateStatus.Unknown] forever.
 *
 * @param currentVersion this build's version (KlardropVersion.VERSION).
 * @param osType used to pick the right download asset for the open-URL fallback.
 * @param fetcher platform manifest fetcher, or null where unsupported.
 * @param detectChannel resolves the install channel (runs subprocesses; called off-main).
 * @param installerFactory builds the in-app self-updater for a channel, or returns
 *   null when self-install isn't possible (then the UI uses the [UpdateAction]).
 * @param manifestUrl the `latest.json` URL; defaults to the stable "latest release" link.
 */
class UpdateChecker(
  private val currentVersion: String,
  private val osType: OsType,
  private val fetcher: UpdateManifestFetcher?,
  private val detectChannel: () -> InstallChannel,
  coroutines: Coroutines,
  private val installerFactory: (InstallChannel) -> UpdateInstaller? = ::createUpdateInstaller,
  private val manifestUrl: String = DEFAULT_MANIFEST_URL,
) {

  private val scope = coroutines.newScope(coroutines.ioDispatcher)

  private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
  val status: StateFlow<UpdateStatus> = _status.asStateFlow()

  private val _install = MutableStateFlow<InstallProgress>(InstallProgress.Idle)
  /** Progress of the in-app self-update, when one is possible. See [InstallProgress]. */
  val install: StateFlow<InstallProgress> = _install.asStateFlow()

  /** The staged self-updater, kept so [applyUpdate] can swap + relaunch. */
  private var installer: UpdateInstaller? = null

  /** Fetch the manifest and update [status]. No-op on platforms without a fetcher. */
  fun checkNow() {
    val f = fetcher ?: return
    scope.launch {
      val manifest = runCatching { f.fetch(manifestUrl) }
        .onFailure { log("UpdateChecker", "manifest fetch failed", it) }
        .getOrNull() ?: return@launch

      if (!isNewerVersion(manifest.version, currentVersion)) {
        _status.value = UpdateStatus.UpToDate
        return@launch
      }

      val channel = runCatching { detectChannel() }.getOrDefault(InstallChannel.UNKNOWN)
      log("UpdateChecker", "update available: $currentVersion -> ${manifest.version} via $channel")
      _status.value = UpdateStatus.Available(
        version = manifest.version,
        channel = channel,
        action = actionFor(channel, manifest),
      )
      maybeSelfInstall(channel, manifest)
    }
  }

  /**
   * If this channel can self-install and the manifest has a matching asset, start
   * downloading + staging it in the background, reflecting progress in [install].
   * On failure the banner falls back to the channel's [UpdateAction].
   */
  private suspend fun maybeSelfInstall(channel: InstallChannel, manifest: LatestManifest) {
    if (_install.value is InstallProgress.Downloading) return
    val inst = runCatching { installerFactory(channel) }.getOrNull() ?: return
    val asset = manifest.platforms["linux-tarball"] ?: return
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
   * Fallback action when the in-app self-updater isn't available (see [install]).
   * For a script (TARBALL) install that can't write its own files, re-running the
   * one-line installer upgrades in place; Homebrew has its own upgrade command;
   * everything else opens the download.
   */
  private fun actionFor(channel: InstallChannel, manifest: LatestManifest): UpdateAction = when (channel) {
    InstallChannel.BREW -> UpdateAction.RunCommand("brew upgrade --cask klardrop")
    InstallChannel.TARBALL -> UpdateAction.RunCommand("curl -fsSL $INSTALL_SCRIPT_URL | bash")
    InstallChannel.DMG,
    InstallChannel.MANUAL,
    InstallChannel.UNKNOWN -> UpdateAction.OpenUrl(downloadUrl(manifest))
  }

  /** Best download link for this OS, falling back to the release notes page. */
  private fun downloadUrl(manifest: LatestManifest): String {
    val p = manifest.platforms
    val asset = when (osType) {
      OsType.APPLE -> p["macos"]
      OsType.WINDOWS -> p["windows"]
      OsType.LINUX -> p["linux-tarball"] ?: p["linux-deb"]
      else -> null
    }
    return asset?.url ?: manifest.notes ?: RELEASES_PAGE
  }

  companion object {
    const val DEFAULT_MANIFEST_URL =
      "https://github.com/carlonzo/klardrop/releases/latest/download/latest.json"
    const val RELEASES_PAGE = "https://github.com/carlonzo/klardrop/releases/latest"

    /** One-line Linux installer; re-running it upgrades in place. */
    const val INSTALL_SCRIPT_URL =
      "https://raw.githubusercontent.com/carlonzo/klardrop/main/packaging/install.sh"
  }
}
