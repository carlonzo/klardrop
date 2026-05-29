package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

private val json = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = UpdateManifestFetcher { url ->
  withContext(Dispatchers.IO) {
    runCatching {
      val client = HttpClient.newBuilder()
        // releases/latest/download/<asset> 302-redirects to the actual asset.
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
      val request = HttpRequest.newBuilder(URI.create(url))
        .GET()
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/json")
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if (response.statusCode() !in 200..299) {
        log("UpdateChecker", "manifest GET ${response.statusCode()} for $url")
        return@runCatching null
      }
      // Explicit serializer (not the reflective decodeFromString<T>) so a missing
      // ProGuard keep rule breaks the minified build instead of failing silently.
      json.decodeFromString(LatestManifest.serializer(), response.body())
    }.onFailure { log("UpdateChecker", "manifest fetch/parse failed", it) }.getOrNull()
  }
}

actual fun detectInstallChannel(): InstallChannel {
  // Flatpak sandboxes set FLATPAK_ID and mount /.flatpak-info.
  if (!System.getenv("FLATPAK_ID").isNullOrEmpty() || File("/.flatpak-info").exists()) {
    return InstallChannel.FLATPAK
  }

  val launcher = currentLauncherPath() ?: return InstallChannel.UNKNOWN
  val os = System.getProperty("os.name", "").lowercase()

  return when {
    os.contains("mac") || os.contains("darwin") -> {
      val inBundle = launcher.contains(".app/")
      when {
        inBundle && runExitCode("brew", "list", "--cask", "klardrop") == 0 -> InstallChannel.BREW
        inBundle -> InstallChannel.DMG
        else -> InstallChannel.MANUAL
      }
    }

    os.contains("nix") || os.contains("nux") || os.contains("aix") -> when {
      // pacman/dpkg own the on-disk launcher when installed via a package.
      runExitCode("pacman", "-Qo", launcher) == 0 -> InstallChannel.AUR
      runExitCode("dpkg", "-S", launcher) == 0 -> InstallChannel.APT
      launcher.startsWith("/opt/") -> InstallChannel.TARBALL
      else -> InstallChannel.MANUAL
    }

    else -> InstallChannel.MANUAL // Windows MSI etc. — no package manager to query.
  }
}

/**
 * The on-disk path of the running launcher, symlinks resolved. For a jpackage
 * app-image this is the native launcher (e.g. /opt/klardrop/bin/klardrop or the
 * bundled runtime's java) — both live inside the package-owned tree, so the
 * pacman/dpkg ownership probes resolve correctly.
 */
private fun currentLauncherPath(): String? = runCatching {
  val cmd = ProcessHandle.current().info().command().orElse(null) ?: return null
  val path = Path.of(cmd)
  val real = runCatching { path.toRealPath() }.getOrDefault(path)
  real.toString()
}.getOrNull()

private fun runExitCode(vararg command: String): Int = runCatching {
  val process = ProcessBuilder(*command)
    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .start()
  if (!process.waitFor(5, TimeUnit.SECONDS)) {
    process.destroyForcibly()
    return -1
  }
  process.exitValue()
}.getOrDefault(-1) // command missing / not executable → treat as "not owned by it"
