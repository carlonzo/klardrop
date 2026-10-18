package com.carlom.klardrop.common.update

/**
 * Fetches and parses the release manifest. Implemented per platform: desktop
 * does an HTTP GET + JSON parse; mobile has no implementation (update checks
 * are desktop-only) and [createUpdateManifestFetcher] returns null there.
 *
 * The implementation parses with the explicit `LatestManifest.serializer()` so
 * minified release builds fail loudly at keep-time, not silently at runtime.
 */
public fun interface UpdateManifestFetcher {
  /** GET + parse [url]; returns null on any network/parse failure. */
  public suspend fun fetch(url: String): LatestManifest?
}

/**
 * Detect how this copy was installed. Desktop inspects the launcher path and
 * the system package DBs; mobile always returns [InstallChannel.UNKNOWN].
 * May run short-lived subprocesses, so callers should invoke it off the main thread.
 */
internal expect fun detectInstallChannel(): InstallChannel

/**
 * The platform's manifest fetcher, or null on platforms that don't support
 * in-app update checks (Android/iOS update through their stores).
 */
internal expect fun createUpdateManifestFetcher(): UpdateManifestFetcher?

/**
 * The platform's release asset key in latest.json ("linux-tarball" on JVM,
 * "linux-native-x64"/"linux-native-arm64" on native linux, per runtime arch).
 */
internal expect val platformUpdateAssetKey: String

/**
 * Detect the platform's installation flavor flag for fallback reinstall commands
 * (e.g. "--qt", "--omarchy", or "--native" on native Linux). Returns null when
 * not applicable (JVM / mobile).
 */
internal expect fun detectPlatformFlavorFlag(): String?


