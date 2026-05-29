package com.carlom.klardrop.common.update

// iOS updates through the App Store; no in-app update check.
actual fun detectInstallChannel(): InstallChannel = InstallChannel.UNKNOWN

actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = null
