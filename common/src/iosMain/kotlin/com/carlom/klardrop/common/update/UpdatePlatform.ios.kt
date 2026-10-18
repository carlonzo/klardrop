package com.carlom.klardrop.common.update

// iOS updates through the App Store; no in-app update check.
internal actual val platformUpdateAssetKey: String = UpdateChecker.ASSET_LINUX_TARBALL

internal actual fun detectInstallChannel(): InstallChannel = InstallChannel.UNKNOWN

internal actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = null

internal actual fun createUpdateInstaller(channel: InstallChannel): UpdateInstaller? = null

internal actual fun detectPlatformFlavorFlag(): String? = null

