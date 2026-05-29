package com.carlom.klardrop.common.update

/**
 * Self-installs an update for channels that own their on-disk files — currently
 * the Linux `install.sh` install, where the app-image lives in a user-writable
 * directory. It downloads the release asset, verifies its sha256, stages the new
 * app-image, and can then swap it in and relaunch.
 *
 * Created per [InstallChannel] by [createUpdateInstaller], which returns null when
 * the running copy can't replace itself (wrong channel, files not writable, a
 * system `/opt` install without privileges, mobile, …). In that case the UI falls
 * back to the channel's [UpdateAction] (a command to copy or a URL to open).
 */
interface UpdateInstaller {

  /**
   * Download [asset], verify its [ReleaseAsset.sha256] (when present), and stage
   * it next to the install. Suspends until staged; throws on any failure.
   *
   * @param onProgress download fraction in 0..1, or null while indeterminate
   *   (e.g. during extraction). Called on a background dispatcher.
   */
  suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit)

  /**
   * Apply the staged update: swap the new app-image in and relaunch. Spawns a
   * short-lived detached helper that waits for this process to exit, then exits
   * the current process — so this call does not return normally.
   */
  fun applyAndRestart()
}

/**
 * The self-updater for this [channel], or null when self-installing isn't possible
 * (see [UpdateInstaller]). Desktop implements it for the Linux script install;
 * mobile always returns null.
 */
expect fun createUpdateInstaller(channel: InstallChannel): UpdateInstaller?
