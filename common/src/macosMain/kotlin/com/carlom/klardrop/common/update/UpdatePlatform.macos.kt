package com.carlom.klardrop.common.update

// macOS v1 distributes through its own channel (App Store / signed DMG) and has
// no in-app update check yet — mirror the iOS stubs.
actual val platformUpdateAssetKey: String = UpdateChecker.ASSET_LINUX_TARBALL

actual fun detectInstallChannel(): InstallChannel = InstallChannel.UNKNOWN

actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = null

actual fun createUpdateInstaller(channel: InstallChannel): UpdateInstaller? = null

actual fun detectPlatformFlavorFlag(): String? = null

