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

    os.contains("nix") || os.contains("nux") || os.contains("aix") -> detectLinuxChannel(launcher)

    os.contains("win") -> InstallChannel.MSI

    else -> InstallChannel.MANUAL
  }
}

/**
 * Which Linux packaging owns this process, in order of confidence.
 *
 * The sandbox/bundle markers come first: inside them the launcher path is a
 * sandbox-internal one (`/app/bin/klardrop` under Flatpak) that no host package
 * database can resolve anyway.
 *
 * The package-ownership probes then run BEFORE the install.sh path check, and the
 * order matters. jpackage's .deb and .rpm both install to `/opt/klardrop` — exactly
 * where a root install.sh install lives — so a path check alone cannot tell them
 * apart, and guessing "tarball" would hand a dpkg-owned install the `curl … | bash`
 * reinstall, silently overwriting files dpkg still believes it owns. Only the
 * package database can answer this, so we ask it.
 *
 * Each probe forks a subprocess, so they are skipped for a launcher under $HOME:
 * a per-user install (the common self-updating case) can never belong to a system
 * package, and that is the path we would otherwise pay three forks to rule out.
 */
private fun detectLinuxChannel(launcher: String): InstallChannel {
  val home = System.getProperty("user.home").orEmpty()
  val underHome = home.isNotEmpty() && launcher.startsWith("$home/")

  return when {
    // Set by the Flatpak runtime for every process in the sandbox; the file exists
    // in the sandbox even when the variable was scrubbed.
    System.getenv("FLATPAK_ID") != null || Files.exists(Path.of("/.flatpak-info")) ->
      InstallChannel.FLATPAK

    // snapd exports these to confined apps, and snaps always run from /snap/<name>/<rev>.
    System.getenv("SNAP") != null || launcher.startsWith("/snap/") -> InstallChannel.SNAP

    // The AppImage runtime exports $APPIMAGE (absolute path of the .AppImage itself)
    // and mounts the payload under a /tmp/.mount_* squashfs.
    System.getenv("APPIMAGE") != null || launcher.startsWith("/tmp/.mount_") ->
      InstallChannel.APPIMAGE

    // A /nix/store path is immutable by construction — never self-updatable.
    launcher.startsWith("/nix/store/") -> InstallChannel.NIX

    // "Which package owns this file?" — a hit means that package manager installed
    // us and must be the one to upgrade us.
    !underHome && runExitCode("dpkg-query", "-S", launcher) == 0 -> InstallChannel.DEB
    !underHome && runExitCode("rpm", "-qf", launcher) == 0 -> InstallChannel.RPM
    !underHome && runExitCode("pacman", "-Qo", launcher) == 0 -> InstallChannel.PACMAN

    // Unowned, and under a root the install.sh script manages: both the native
    // launcher and the bundled runtime's java live inside that app-image.
    linuxInstallRoot(launcher) != null -> InstallChannel.TARBALL

    else -> InstallChannel.MANUAL
  }
}

/**
 * If [launcher] lives inside an install.sh-managed app-image, the root of that
 * app-image (`…/klardrop`); otherwise null. Matches the two scopes the script
 * installs to: system-wide `/opt/klardrop` and per-user `~/.local/lib/klardrop`.
 *
 * Deliberately does NOT match the pre-relocation `~/.local/share/klardrop` root:
 * that directory also holds the app's own data (databases, preferences), and the
 * swap in [DesktopTarballInstaller.applyAndRestart] replaces the whole tree — it
 * would take the device identity and message history with it. Returning null there
 * falls the UI back to re-running install.sh, which relocates and keeps the data.
 */
private fun linuxInstallRoot(launcher: String): Path? {
  val home = System.getProperty("user.home").orEmpty()
  return when {
    launcher.startsWith("/opt/klardrop/") -> Path.of("/opt/klardrop")
    home.isNotEmpty() && launcher.startsWith("$home/.local/lib/klardrop/") ->
      Path.of(home, ".local", "lib", "klardrop")
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
 * @param appDir the live app-image root (e.g. ~/.local/lib/klardrop).
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
