package com.carlom.klardrop.common.update

/**
 * Fetches and parses the release manifest. Implemented per platform: desktop
 * does an HTTP GET + JSON parse; mobile has no implementation (update checks
 * are desktop-only) and [createUpdateManifestFetcher] returns null there.
 *
 * The implementation parses with the explicit `LatestManifest.serializer()` so
 * minified release builds fail loudly at keep-time, not silently at runtime.
 */
fun interface UpdateManifestFetcher {
  /** GET + parse [url]; returns null on any network/parse failure. */
  suspend fun fetch(url: String): LatestManifest?
}

/**
 * Detect how this copy was installed. Desktop inspects the launcher path and
 * the system package DBs; mobile always returns [InstallChannel.UNKNOWN].
 * May run short-lived subprocesses, so callers should invoke it off the main thread.
 */
expect fun detectInstallChannel(): InstallChannel

/**
 * The platform's manifest fetcher, or null on platforms that don't support
 * in-app update checks (Android/iOS update through their stores).
 */
expect fun createUpdateManifestFetcher(): UpdateManifestFetcher?
