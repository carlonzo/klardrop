package com.carlom.klardrop.common.update

// iOS updates through the App Store; no in-app update check.
actual val platformUpdateAssetKey: String = UpdateChecker.ASSET_LINUX_TARBALL

actual fun detectInstallChannel(): InstallChannel = InstallChannel.UNKNOWN

actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = null

actual fun createUpdateInstaller(channel: InstallChannel): UpdateInstaller? = null

actual fun detectPlatformFlavorFlag(): String? = null

