package com.carlom.klardrop.common.update

// Android updates through the Play Store; no in-app update check.
actual fun detectInstallChannel(): InstallChannel = InstallChannel.UNKNOWN

actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = null
