package com.carlom.klardrop.common.update

// macOS v1 distributes through its own channel (App Store / signed DMG) and has
// no in-app update check yet — mirror the iOS stubs.
internal actual val platformUpdateAssetKey: String = UpdateChecker.ASSET_LINUX_TARBALL

internal actual fun detectInstallChannel(): InstallChannel = InstallChannel.UNKNOWN

internal actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = null

internal actual fun createUpdateInstaller(channel: InstallChannel): UpdateInstaller? = null

internal actual fun detectPlatformFlavorFlag(): String? = null

