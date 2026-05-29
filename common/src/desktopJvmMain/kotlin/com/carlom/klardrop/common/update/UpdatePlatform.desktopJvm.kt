package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

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

    os.contains("nix") || os.contains("nux") || os.contains("aix") ->
      // The install.sh script puts the app-image under one of these roots; both
      // the native launcher and the bundled runtime's java live inside it.
      if (linuxInstallRoot(launcher) != null) InstallChannel.TARBALL else InstallChannel.MANUAL

    else -> InstallChannel.MANUAL // Windows MSI etc. — no in-place self-update.
  }
}

/**
 * If [launcher] lives inside an install.sh-managed app-image, the root of that
 * app-image (`…/klardrop`); otherwise null. Matches the two scopes the script
 * installs to: system-wide `/opt/klardrop` and per-user `~/.local/share/klardrop`.
 */
private fun linuxInstallRoot(launcher: String): Path? {
  val home = System.getProperty("user.home").orEmpty()
  return when {
    launcher.startsWith("/opt/klardrop/") -> Path.of("/opt/klardrop")
    home.isNotEmpty() && launcher.startsWith("$home/.local/share/klardrop/") ->
      Path.of(home, ".local", "share", "klardrop")
    else -> null
  }
}

actual fun createUpdateInstaller(channel: InstallChannel): UpdateInstaller? {
  if (channel != InstallChannel.TARBALL) return null
  val launcher = currentLauncherPath() ?: return null
  val appDir = linuxInstallRoot(launcher) ?: return null
  val parent = appDir.parent ?: return null
  // Self-update only when we can replace the app-image in place — i.e. a per-user
  // install. A root-owned /opt install isn't writable, so we fall back to re-running
  // the installer (which can sudo). Stage next to the app-image so the final move is
  // a same-filesystem rename.
  return runCatching {
    if (!Files.isWritable(appDir) || !Files.isWritable(parent)) return null
    DesktopTarballInstaller(
      appDir = appDir,
      parent = parent,
      relaunch = appDir.resolve("bin").resolve("klardrop"),
    )
  }.getOrNull()
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

/**
 * Self-updater for a per-user Linux install.sh install. Downloads the universal
 * tarball, verifies its sha256, and stages the new app-image as `<parent>/klardrop.new`.
 * [applyAndRestart] hands off to a detached shell that waits for this process to exit,
 * swaps the directory, and relaunches.
 *
 * @param appDir the live app-image root (e.g. ~/.local/share/klardrop).
 * @param parent appDir's parent — staging happens here so the final move is a rename.
 * @param relaunch the native launcher to exec after the swap (appDir/bin/klardrop).
 */
private class DesktopTarballInstaller(
  private val appDir: Path,
  private val parent: Path,
  private val relaunch: Path,
) : UpdateInstaller {

  private val staged: Path = parent.resolve("klardrop.new")

  override suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit) {
    withContext(Dispatchers.IO) {
      val tarball = Files.createTempFile(parent, "klardrop-update", ".tar.gz")
      try {
        downloadVerified(asset, tarball, onProgress)

        onProgress(null) // extracting — indeterminate
        val extractDir = Files.createTempDirectory(parent, "klardrop-extract")
        try {
          val rc = ProcessBuilder("tar", "-xzf", tarball.toString(), "-C", extractDir.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
          check(rc == 0) { "tar extraction failed (exit $rc)" }

          // Tarball top-level layout: klardrop-linux-x64/klardrop/{bin,lib,...}
          val newImage = extractDir.resolve("klardrop-linux-x64").resolve("klardrop")
          check(Files.isDirectory(newImage)) { "unexpected tarball layout" }

          deleteRecursively(staged)
          Files.move(newImage, staged)
        } finally {
          deleteRecursively(extractDir)
        }
      } finally {
        runCatching { Files.deleteIfExists(tarball) }
      }
    }
  }

  /** Stream [asset] to [dest], updating [onProgress], and verify its sha256. */
  private fun downloadVerified(asset: ReleaseAsset, dest: Path, onProgress: (Float?) -> Unit) {
    val client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(20))
      .build()
    val request = HttpRequest.newBuilder(URI.create(asset.url))
      .GET()
      .timeout(Duration.ofMinutes(10))
      .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    check(response.statusCode() in 200..299) { "download HTTP ${response.statusCode()}" }

    val total = asset.size
      ?: response.headers().firstValueAsLong("content-length").orElse(-1L)
    val digest = MessageDigest.getInstance("SHA-256")
    response.body().use { input ->
      Files.newOutputStream(dest).use { out ->
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          out.write(buffer, 0, n)
          digest.update(buffer, 0, n)
          read += n
          onProgress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null)
        }
      }
    }

    val expected = asset.sha256?.lowercase()
    if (expected != null) {
      val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
      check(actual == expected) { "checksum mismatch (expected $expected, got $actual)" }
    }
  }

  override fun applyAndRestart() {
    val pid = ProcessHandle.current().pid()
    // bash dollars are escaped (\$) so Kotlin doesn't interpolate them; paths come
    // in as positional args so spaces are handled by the quoting.
    val swap = "pid=\"\$1\"; target=\"\$2\"; staged=\"\$3\"; launcher=\"\$4\"; " +
      "for i in \$(seq 1 200); do kill -0 \"\$pid\" 2>/dev/null || break; sleep 0.1; done; " +
      "rm -rf \"\$target.bak\"; " +
      "mv \"\$target\" \"\$target.bak\" && mv \"\$staged\" \"\$target\" && rm -rf \"\$target.bak\"; " +
      "exec \"\$launcher\""
    runCatching {
      ProcessBuilder(
        "bash", "-c", swap, "klardrop-update",
        pid.toString(), appDir.toString(), staged.toString(), relaunch.toString(),
      )
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    }.onFailure { log("UpdateChecker", "failed to launch update swap", it); return }
    exitProcess(0)
  }

  private fun deleteRecursively(path: Path) {
    runCatching { path.toFile().deleteRecursively() }
  }
}
