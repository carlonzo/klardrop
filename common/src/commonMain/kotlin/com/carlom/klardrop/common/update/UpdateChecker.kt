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
 * @param manifestUrl the `latest.json` URL; defaults to the stable "latest release" link.
 */
class UpdateChecker(
  private val currentVersion: String,
  private val osType: OsType,
  private val fetcher: UpdateManifestFetcher?,
  private val detectChannel: () -> InstallChannel,
  coroutines: Coroutines,
  private val manifestUrl: String = DEFAULT_MANIFEST_URL,
) {

  private val scope = coroutines.newScope(coroutines.ioDispatcher)

  private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Unknown)
  val status: StateFlow<UpdateStatus> = _status.asStateFlow()

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
    }
  }

  private fun actionFor(channel: InstallChannel, manifest: LatestManifest): UpdateAction = when (channel) {
    InstallChannel.AUR -> UpdateAction.RunCommand("yay -S klardrop-bin")
    InstallChannel.APT -> UpdateAction.RunCommand("sudo apt update && sudo apt install --only-upgrade klardrop")
    InstallChannel.FLATPAK -> UpdateAction.RunCommand("flatpak update com.carlom.Klardrop")
    InstallChannel.BREW -> UpdateAction.RunCommand("brew upgrade --cask klardrop")
    InstallChannel.DMG,
    InstallChannel.TARBALL,
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
  }
}
