@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.execProcess
import com.carlom.klardrop.common.utils.log
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.cinterop.*
import kotlinx.serialization.json.Json
import platform.posix.*
import kotlin.native.CpuArchitecture
import kotlin.native.Platform
import kotlin.time.TimeSource

private val json = Json {
  ignoreUnknownKeys = true
  isLenient = true
}

actual val platformUpdateAssetKey: String =
  if (Platform.cpuArchitecture == CpuArchitecture.ARM64) UpdateChecker.ASSET_LINUX_NATIVE_ARM64
  else UpdateChecker.ASSET_LINUX_NATIVE_X64

actual fun createUpdateManifestFetcher(): UpdateManifestFetcher? = UpdateManifestFetcher { url ->
  if (!url.startsWith("https://", ignoreCase = true)) {
    log("UpdateChecker", "manifest fetch refused non-https url: $url")
    return@UpdateManifestFetcher null
  }
  runCatching {
    val result = execProcess(
      argv = listOf("curl", "-fsSL", "--proto", "=https", "--proto-redir", "=https", "--max-time", "15", "-H", "Accept: application/json", url),
      timeoutMillis = 20_000L,
    )
    if (result.exitCode != 0) {
      log("UpdateChecker", "manifest curl exit ${result.exitCode}: ${result.stderrString}")
      return@runCatching null
    }
    json.decodeFromString(LatestManifest.serializer(), result.stdoutString)
  }.onFailure {
    log("UpdateChecker", "manifest fetch/parse failed", it)
  }.getOrNull()
}

class SecurityException(message: String) : RuntimeException(message)

@OptIn(ExperimentalForeignApi::class)
internal fun currentExecutablePath(): String? {
  memScoped {
    val bufSize = 4096
    val buf = allocArray<ByteVar>(bufSize)
    val len = readlink("/proc/self/exe", buf, bufSize.toULong())
    if (len <= 0) return null
    return buf.readBytes(len.toInt()).decodeToString()
  }
}

private fun runExitCode(vararg cmd: String): Int =
  runCatching { execProcess(cmd.toList()).exitCode }.getOrDefault(-1)

@OptIn(ExperimentalForeignApi::class)
internal fun detectFilePackageChannel(path: String): InstallChannel? {
  if (access(path, F_OK) != 0) return null
  if (runExitCode("pacman", "-Qo", path) == 0) return InstallChannel.PACMAN
  if (runExitCode("dpkg-query", "-S", path) == 0) return InstallChannel.DEB
  if (runExitCode("rpm", "-qf", path) == 0) return InstallChannel.RPM
  return null
}

internal fun isFilePackageManaged(
  path: String,
  packageChecker: (String) -> InstallChannel? = ::detectFilePackageChannel,
): Boolean = packageChecker(path) != null

@OptIn(ExperimentalForeignApi::class)
internal fun detectLinuxInstallChannel(
  exePath: String?,
  home: String? = getenv("HOME")?.toKString(),
  packageChecker: (String) -> InstallChannel? = ::detectFilePackageChannel,
): InstallChannel {
  if (exePath == null || home.isNullOrBlank()) return InstallChannel.MANUAL

  val pkgChannel = packageChecker(exePath)
  if (pkgChannel != null) return pkgChannel

  // The tarball channel owns TWO files, and they are always written and replaced
  // together: `klardrop-engine` (this Kotlin/Native daemon) and `klardrop` (the
  // native Rust CLI). Either one running from here means "installed by us".
  return if (exePath in tarballInstallPaths(home)) {
    InstallChannel.TARBALL
  } else {
    InstallChannel.MANUAL
  }
}

/**
 * The install's `bin/klardrop-engine` and `bin/klardrop` under [home]. Kept as a
 * pair: an install whose engine and client cannot both be replaced in place is not
 * a copy this updater may touch.
 */
internal fun tarballInstallPaths(home: String): List<String> = listOf(
  "$home/.local/bin/klardrop-engine",
  "$home/.local/bin/klardrop",
)

actual fun detectInstallChannel(): InstallChannel =
  detectLinuxInstallChannel(currentExecutablePath())

enum class LinuxFlavor {
  NATIVE,
  OMARCHY,
  QT,
}

@OptIn(ExperimentalForeignApi::class)
internal fun readMarkerFileSafe(path: String): String? = memScoped {
  val st = alloc<stat>()
  if (lstat(path, st.ptr) != 0) return null
  // Reject symlinks, FIFOs, directories, and non-regular files
  if ((st.st_mode and S_IFMT.toUInt()) != S_IFREG.toUInt()) return null
  // Bound file size: installer marker contains a short single-line string (e.g. <= 64 bytes)
  if (st.st_size < 0 || st.st_size > 64) return null

  // Open with O_NONBLOCK so opening a FIFO or special file never blocks,
  // and O_NOFOLLOW to guard against symlink race
  val fd = open(path, O_RDONLY or O_NONBLOCK or O_CLOEXEC or O_NOFOLLOW)
  if (fd < 0) return null
  try {
    val fst = alloc<stat>()
    if (fstat(fd, fst.ptr) != 0) return null
    if ((fst.st_mode and S_IFMT.toUInt()) != S_IFREG.toUInt()) return null
    if (fst.st_size < 0 || fst.st_size > 64) return null

    val maxBytes = 64
    val buf = allocArray<ByteVar>(maxBytes + 1)
    val n = read(fd, buf, maxBytes.toULong())
    if (n < 0) return null
    if (n == 0L) return "" // Legacy empty marker

    val readCount = n.toInt()
    for (i in 0 until readCount) {
      if (buf[i] == 0.toByte()) return null // Reject NUL byte
    }
    val content = buf.readBytes(readCount).decodeToString()
    val raw = when {
      content.endsWith("\r\n") -> content.substring(0, content.length - 2)
      content.endsWith("\n") -> content.substring(0, content.length - 1)
      else -> content
    }
    if (raw.contains('\n') || raw.contains('\r')) return null // Reject multiple lines
    if (raw == "qt" || raw == "omarchy" || raw == "native") {
      return raw
    }
    return null
  } finally {
    close(fd)
  }
}

internal fun resolveEffectiveXdgDataHome(home: String, xdgDataHome: String? = null): String {
  if (!xdgDataHome.isNullOrBlank()) return xdgDataHome
  return if (home == getenv("HOME")?.toKString()) {
    getenv("XDG_DATA_HOME")?.toKString()?.ifBlank { null } ?: "$home/.local/share"
  } else {
    "$home/.local/share"
  }
}

@OptIn(ExperimentalForeignApi::class)
internal fun detectLinuxFlavor(
  home: String? = getenv("HOME")?.toKString(),
  xdgDataHome: String? = null,
  markerReader: (String) -> String? = ::readMarkerFileSafe,
  fileExists: (String) -> Boolean = ::fileExists,
  dirExists: (String) -> Boolean = ::isDirectory,
): LinuxFlavor {
  if (home.isNullOrBlank()) return LinuxFlavor.NATIVE
  val effectiveXdgDataHome = resolveEffectiveXdgDataHome(home, xdgDataHome)
  val markerPath = "$effectiveXdgDataHome/klardrop/.installer-marker"
  val stored = markerReader(markerPath)
  if (stored != null) {
    val trimmed = stored.trim()
    if (trimmed == "qt") return LinuxFlavor.QT
    if (trimmed == "omarchy") return LinuxFlavor.OMARCHY
    if (trimmed == "native") return LinuxFlavor.NATIVE
    // Legacy empty marker: retain old headless/Omarchy behavior
    if (dirExists("$home/.config/omarchy/plugins/klardrop.omarchy") || fileExists("$home/.local/bin/klardrop-omarchy-share")) return LinuxFlavor.OMARCHY
    return LinuxFlavor.NATIVE
  }
  // No marker: retain old headless/Omarchy behavior (never infer Qt from mere binary existence)
  if (dirExists("$home/.config/omarchy/plugins/klardrop.omarchy") || fileExists("$home/.local/bin/klardrop-omarchy-share")) return LinuxFlavor.OMARCHY
  return LinuxFlavor.NATIVE
}

actual fun detectPlatformFlavorFlag(): String? =
  when (detectLinuxFlavor()) {
    LinuxFlavor.QT -> "--qt"
    LinuxFlavor.OMARCHY -> "--omarchy"
    LinuxFlavor.NATIVE -> "--native"
  }

@OptIn(ExperimentalForeignApi::class)
internal fun pathExistsLstat(path: String): Boolean = memScoped {
  val st = alloc<stat>()
  lstat(path, st.ptr) == 0
}

@OptIn(ExperimentalForeignApi::class)
internal fun isSymlink(path: String): Boolean = memScoped {
  val st = alloc<stat>()
  if (lstat(path, st.ptr) != 0) return false
  (st.st_mode and S_IFMT.toUInt()) == S_IFLNK.toUInt()
}

/**
 * False when [path] must not be written over: a package-owned file, a symlink (the
 * install may legitimately be a symlink into /opt, in which case the package
 * manager owns it), or anything that is not a regular file.
 */
internal fun isReplaceableBinary(path: String): Boolean {
  if (isFilePackageManaged(path)) return false
  if (!pathExistsLstat(path)) return true
  return isRegularFile(path) && !isSymlink(path)
}

@OptIn(ExperimentalForeignApi::class)
actual fun createUpdateInstaller(channel: InstallChannel): UpdateInstaller? {
  if (channel != InstallChannel.TARBALL) return null
  val home = getenv("HOME")?.toKString()?.ifBlank { null } ?: return null
  val binDir = "$home/.local/bin"
  // Refuse BEFORE anything is downloaded or staged, and refuse when only ONE of the
  // pair is replaceable: replacing the engine without the client (or the other way
  // round) is exactly how a v1 client ends up sitting next to a v2 daemon.
  for (path in tarballInstallPaths(home)) {
    if (!isReplaceableBinary(path)) return null
  }
  val xdgData = getenv("XDG_DATA_HOME")?.toKString()?.ifBlank { null }
  val flavor = detectLinuxFlavor(home, xdgDataHome = xdgData)
  if (flavor == LinuxFlavor.QT) {
    val targetQtBin = "$binDir/klardrop-qt"
    val targetQtLauncher = "$binDir/klardrop-qt-launcher"
    if (isFilePackageManaged(targetQtBin) || (pathExistsLstat(targetQtBin) && isSymlink(targetQtBin))) return null
    if (isFilePackageManaged(targetQtLauncher) || (pathExistsLstat(targetQtLauncher) && isSymlink(targetQtLauncher))) return null
  }
  val isWritable = if (access(binDir, F_OK) == 0) {
    access(binDir, W_OK) == 0
  } else {
    val localDir = "$home/.local"
    if (access(localDir, F_OK) == 0) {
      access(localDir, W_OK) == 0
    } else {
      access(home, W_OK) == 0
    }
  }
  if (!isWritable) return null
  return LinuxNativeTarballInstaller(homeDir = home, flavor = flavor, xdgDataHome = xdgData)
}

private val TAR_ENTRY_REGEX = Regex("""^([-d])[rwxsStT-]{9} \S+ +\d+ \d{4}-\d{2}-\d{2} \d{2}:\d{2} (.+)$""")

/**
 * Test-only binary (see cli-rust/Cargo.toml). It is not user-facing and must never
 * be installed, so a tarball that carries it is refused outright — matching
 * `klardrop-fixture-daemon-<hash>` cargo's suffixed build names too.
 */
private const val FORBIDDEN_TAR_ENTRY = "klardrop-fixture-daemon"

/** Bytes needed to reach `e_machine` in an ELF header (16 ident + 2 type + 2 machine). */
private const val ELF_HEADER_MIN_BYTES = 20

/** `EM_X86_64` */
private const val EM_X86_64 = 0x3E

/** `EM_AARCH64` */
private const val EM_AARCH64 = 0xB7

internal fun validateTarEntries(entries: List<String>) {
  for (rawLine in entries) {
    val line = rawLine.trimEnd('\r', '\n')
    if (line.isBlank()) continue
    val match = TAR_ENTRY_REGEX.matchEntire(line)
      ?: throw SecurityException("Malicious or invalid tar entry: '$rawLine'")
    val path = match.groupValues[2]
    if (path.contains(" -> ") || path.contains(" link to ")) {
      throw SecurityException("Malicious tar entry: symlink or hardlink detected in '$rawLine'")
    }
    if (path.startsWith("/") || path.startsWith("\\")) {
      throw SecurityException("Malicious tar entry: absolute path '$path'")
    }
    val segments = path.split('/', '\\')
    for (segment in segments) {
      if (segment == "..") {
        throw SecurityException("Malicious tar entry: path traversal segment '..' in '$path'")
      }
      if (segment.startsWith(FORBIDDEN_TAR_ENTRY)) {
        throw SecurityException("Forbidden tar entry: test-only binary '$segment' in '$path'")
      }
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
internal fun isRegularFile(path: String): Boolean = memScoped {
  val st = alloc<stat>()
  if (lstat(path, st.ptr) != 0) return false
  (st.st_mode and S_IFMT.toUInt()) == S_IFREG.toUInt()
}

@OptIn(ExperimentalForeignApi::class)
internal fun isDirectory(path: String): Boolean = memScoped {
  val st = alloc<stat>()
  if (lstat(path, st.ptr) != 0) return false
  (st.st_mode and S_IFMT.toUInt()) == S_IFDIR.toUInt()
}

@OptIn(ExperimentalForeignApi::class)
internal fun makeTempDir(parentDir: String, prefix: String = "tmp"): String = memScoped {
  val template = "$parentDir/$prefix.XXXXXX"
  val bytes = template.encodeToByteArray()
  val buf = allocArray<ByteVar>(bytes.size + 1)
  memcpy(buf, bytes.refTo(0), bytes.size.toULong())
  buf[bytes.size] = 0.toByte()
  val res = mkdtemp(buf) ?: throw IllegalStateException("mkdtemp failed for $template (errno ${posix_errno()})")
  res.toKString()
}

@OptIn(ExperimentalForeignApi::class)
internal fun makeTempFile(parentDir: String, prefix: String = "tmp"): String = memScoped {
  val template = "$parentDir/$prefix.XXXXXX"
  val bytes = template.encodeToByteArray()
  val buf = allocArray<ByteVar>(bytes.size + 1)
  memcpy(buf, bytes.refTo(0), bytes.size.toULong())
  buf[bytes.size] = 0.toByte()
  val fd = mkstemp(buf)
  if (fd < 0) throw IllegalStateException("mkstemp failed for $template (errno ${posix_errno()})")
  close(fd)
  buf.toKString()
}

@OptIn(ExperimentalForeignApi::class)
internal fun listDirectory(dirPath: String): List<String> = memScoped {
  val dir = opendir(dirPath) ?: return emptyList()
  val entries = mutableListOf<String>()
  try {
    while (true) {
      val entry = readdir(dir) ?: break
      val name = entry.pointed.d_name.toKString()
      if (name != "." && name != "..") {
        entries.add(name)
      }
    }
  } finally {
    closedir(dir)
  }
  entries
}

private fun mkdirs(path: String) {
  val res = execProcess(listOf("mkdir", "-p", path))
  if (res.exitCode != 0) {
    throw IllegalStateException("Failed to create directory $path (exit ${res.exitCode}): ${res.stderrString}")
  }
}

private fun rmRf(vararg paths: String) {
  require(paths.all { it.startsWith("/") && it.trim('/').isNotEmpty() })
  val res = execProcess(listOf("rm", "-rf") + paths)
  if (res.exitCode != 0) {
    throw IllegalStateException("Failed to remove paths ${paths.joinToString()} (exit ${res.exitCode}): ${res.stderrString}")
  }
}

@OptIn(ExperimentalForeignApi::class)
internal fun fileExists(path: String): Boolean = access(path, F_OK) == 0

@OptIn(ExperimentalForeignApi::class)
internal fun isExecutable(path: String): Boolean = access(path, X_OK) == 0

@OptIn(ExperimentalForeignApi::class)
internal suspend fun computeFileSha256(filePath: String): String {
  val hasher = CryptographyProvider.Default.get(SHA256).hasher()
  val function = hasher.createHashFunction()
  val fd = open(filePath, O_RDONLY)
  if (fd < 0) throw IllegalStateException("Failed to open file for hashing: $filePath (errno ${posix_errno()})")
  try {
    memScoped {
      val bufSize = 64 * 1024
      val buf = allocArray<ByteVar>(bufSize)
      while (true) {
        val n = read(fd, buf, bufSize.toULong())
        if (n < 0) throw IllegalStateException("Failed to read file for hashing: $filePath (errno ${posix_errno()})")
        if (n == 0L) break
        val chunk = buf.readBytes(n.toInt())
        function.update(chunk)
      }
    }
    val digest = function.use { it.hashToByteArray() }
    return digest.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
  } finally {
    close(fd)
  }
}

private val DEFAULT_RESTART_COMMAND = listOf("systemctl", "--user", "restart", "--no-block", "klardrop.service")

@OptIn(ExperimentalForeignApi::class)
internal fun isStartedBySystemd(): Boolean {
  val invocationId = getenv("INVOCATION_ID")?.toKString()
  if (invocationId.isNullOrBlank()) return false
  val result = execProcess(listOf("systemctl", "--user", "is-active", "klardrop.service"))
  return result.exitCode == 0 && result.stdoutString.trim() == "active"
}

@OptIn(ExperimentalForeignApi::class)
class LinuxNativeTarballInstaller(
  private val homeDir: String,
  private val restartCommand: List<String> = DEFAULT_RESTART_COMMAND,
  private val downloader: (suspend (url: String, destFile: String, onProgress: (Float?) -> Unit) -> Unit)? = null,
  private val isSystemdManaged: () -> Boolean = ::isStartedBySystemd,
  private val isPackageManaged: (String) -> Boolean = ::isFilePackageManaged,
  private val flavor: LinuxFlavor = detectLinuxFlavor(homeDir),
  private val renameFile: (String, String) -> Int = { from, to -> rename(from, to) },
  private val xdgDataHome: String? = null,
  private val markerReader: (String) -> String? = ::readMarkerFileSafe,
) : UpdateInstaller {

  init {
    require(homeDir.startsWith("/") && homeDir.trimEnd('/').isNotEmpty())
  }

  private val effectiveXdgDataHome: String
    get() = resolveEffectiveXdgDataHome(homeDir, xdgDataHome)

  private fun requireCurrentQtMarker(phase: String) {
    if (flavor == LinuxFlavor.QT) {
      val markerPath = "$effectiveXdgDataHome/klardrop/.installer-marker"
      val current = markerReader(markerPath)
      if (current != "qt") {
        throw IllegalStateException(
          "Refusing Qt update $phase: installer marker is no longer 'qt' (marker is ${current ?: "missing/invalid"}: $markerPath)"
        )
      }
    }
  }

  private fun updateIconSafe(srcIcon: String, iconDir: String, targetIcon: String) {
    if (!isRegularFile(srcIcon)) return
    if (isPackageManaged(targetIcon)) return
    if (pathExistsLstat(targetIcon)) {
      if (isSymlink(targetIcon) || !isRegularFile(targetIcon)) {
        return
      }
    }
    mkdirs(iconDir)
    execProcess(listOf("cp", "-f", srcIcon, targetIcon))
  }

  private val binDir: String get() = "$homeDir/.local/bin"

  /** The Kotlin/Native daemon — the very process running this installer. */
  private val targetEngine: String get() = "$binDir/klardrop-engine"

  /** The native Rust CLI — the user-facing `klardrop` users type. */
  private val targetClient: String get() = "$binDir/klardrop"
  private val targetQtBin: String get() = "$binDir/klardrop-qt"
  private val targetQtLauncher: String get() = "$binDir/klardrop-qt-launcher"
  private val stagedEngine: String get() = "$binDir/.klardrop-engine.new"
  private val stagedClient: String get() = "$binDir/.klardrop.new"
  private val stagedQtBin: String get() = "$binDir/.klardrop-qt.new"
  private val stagedQtLauncher: String get() = "$binDir/.klardrop-qt-launcher.new"
  private val stagedDir: String get() = "$binDir/.klardrop-staged-tree"

  /**
   * The engine and the CLI as one unit: staged together, replaced back to back, and
   * rolled back together, so no reader ever sees one version of one beside another
   * version of the other. Order is the replacement order; rollback walks it in
   * reverse.
   */
  private data class BinarySlot(
    val label: String,
    val target: String,
    val staged: String,
    /** Path inside the release tarball's top-level directory. */
    val relative: String,
  )

  private fun binarySlots(): List<BinarySlot> = listOf(
    BinarySlot("engine", targetEngine, stagedEngine, "bin/klardrop-engine"),
    BinarySlot("client", targetClient, stagedClient, "bin/klardrop"),
  )

  /** The executable bit every staged binary needs. */
  private val execMode: UInt
    get() = (S_IRWXU or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH).toUInt()

  /** One file the swap replaces: its target, its staged counterpart and its backup. */
  private class ReplaceUnit(
    val label: String,
    val target: String,
    val staged: String,
    val backupPrefix: String,
  ) {
    var hadOld: Boolean = false
    var backup: String? = null
    var replaced: Boolean = false
  }

  /**
   * A tarball entry must be a regular, executable file before it is staged or
   * swapped in. `chmod` is retried because tar's `--no-same-permissions`
   * extraction can leave a mode tighter than the release tarball carried.
   */
  private fun requireExecutableBinary(path: String, label: String) {
    if (!isRegularFile(path)) {
      throw IllegalStateException("Unexpected tarball layout: missing $label executable")
    }
    if (!isExecutable(path)) {
      chmod(path, execMode)
      if (!isExecutable(path)) {
        throw IllegalStateException("Unexpected tarball layout: $label is not executable")
      }
    }
  }

  /**
   * Refuse when either binary of the pair is package-owned, a symlink, or not a
   * regular file. Called before the download starts (not after it) so a refused
   * update never writes anything at all, and again before the swap in case the
   * target changed while the download was in flight.
   */
  private fun requireReplaceableTargets(phase: String) {
    for (slot in binarySlots()) {
      if (isPackageManaged(slot.target)) {
        throw IllegalStateException(
          "Refusing to overwrite package-managed binary (${slot.label}) during $phase: ${slot.target}"
        )
      }
      if (!pathExistsLstat(slot.target)) continue
      if (isSymlink(slot.target)) {
        throw IllegalStateException("Target ${slot.label} binary is a symlink during $phase: ${slot.target}")
      }
      if (!isRegularFile(slot.target)) {
        throw IllegalStateException(
          "Target ${slot.label} binary is not a regular file during $phase: ${slot.target}"
        )
      }
    }
  }

  private fun validateStagedBinaries() {
    for (slot in binarySlots()) {
      if (!isRegularFile(slot.staged) || !isExecutable(slot.staged)) {
        throw IllegalStateException("Staged update missing or incomplete ${slot.label} binary (${slot.staged})")
      }
    }
  }

  /**
   * [path]'s ELF `e_machine`, or null when the file is not an ELF object (or is
   * too short to carry the header). Read straight from the file rather than via
   * `readelf`/`file`: no subprocess, no PATH dependency, and the answer is the same
   * everywhere the updater runs.
   */
  private fun elfMachine(path: String): Int? {
    val fd = open(path, O_RDONLY or O_CLOEXEC)
    if (fd < 0) return null
    try {
      memScoped {
        val buf = allocArray<ByteVar>(ELF_HEADER_MIN_BYTES)
        val n = read(fd, buf, ELF_HEADER_MIN_BYTES.toULong())
        if (n < ELF_HEADER_MIN_BYTES) return null
        val bytes = buf.readBytes(ELF_HEADER_MIN_BYTES)
        if (bytes[0] != 0x7F.toByte() ||
          bytes[1] != 'E'.code.toByte() ||
          bytes[2] != 'L'.code.toByte() ||
          bytes[3] != 'F'.code.toByte()
        ) {
          return null
        }
        val littleEndian = bytes[5].toInt() == 1
        val low = bytes[18].toInt() and 0xFF
        val high = bytes[19].toInt() and 0xFF
        return if (littleEndian) low or (high shl 8) else high or (low shl 8)
      }
    } finally {
      close(fd)
    }
  }

  /**
   * Reject a wrong-architecture binary while it is still staged — before it
   * replaces anything on disk, where an ELF for the wrong machine would leave an
   * install that cannot start. Mirrors the check packaging/linux/stage-native-tarball.sh
   * runs at release time; this is the last line of defence for an install that
   * predates the engine/CLI split, or for a mis-published asset.
   */
  private fun verifyStagedArch(slot: BinarySlot) {
    val machine = elfMachine(slot.staged)
    if (machine == null) {
      // Not an ELF object at all, so there is no architecture to compare. The bytes
      // are already pinned by the manifest's mandatory sha256; say so rather than
      // pass a payload we could not inspect in silence.
      log("UpdateChecker", "staged ${slot.label} binary is not an ELF file (${slot.staged}); architecture check skipped")
      return
    }
    val expected = if (Platform.cpuArchitecture == CpuArchitecture.ARM64) EM_AARCH64 else EM_X86_64
    if (machine != expected) {
      throw IllegalStateException(
        "Staged ${slot.label} binary is built for ELF machine 0x${machine.toString(16)}, " +
          "not this machine (0x${expected.toString(16)}): ${slot.staged}"
      )
    }
  }

  override suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit) {
    requireCurrentQtMarker("staging")
    if (!asset.url.startsWith("https://", ignoreCase = true)) {
      throw SecurityException("Refusing non-HTTPS asset URL: ${asset.url}")
    }
    val expectedSha = asset.sha256?.lowercase()
    if (expectedSha.isNullOrBlank()) {
      throw IllegalStateException("Refusing update asset without sha256 checksum")
    }
    check(homeDir.isNotBlank()) { "HOME directory must not be empty" }
    // Ownership/symlink refusal happens FIRST, before a byte is downloaded or a
    // staging file is created, and it covers both binaries of the pair.
    requireReplaceableTargets("staging")

    mkdirs(binDir)

    for (name in listDirectory(binDir)) {
      if (name.startsWith(".klardrop-download.") ||
        name.startsWith(".klardrop-extract.")
      ) {
        runCatching { rmRf("$binDir/$name") }
      }
    }

    val targetPlugin = "$homeDir/.config/omarchy/plugins/klardrop.omarchy"
    val hasPluginInstalled = isDirectory(targetPlugin)

    val pluginsDir = "$homeDir/.config/omarchy/plugins"
    if (isDirectory(pluginsDir)) {
      runCatching {
        for (name in listDirectory(pluginsDir)) {
          if (name.startsWith(".klardrop.omarchy.staged.")) {
            runCatching { rmRf("$pluginsDir/$name") }
          }
        }
      }
    }

    val tempTarball = makeTempFile(binDir, ".klardrop-download")
    val extractDir = makeTempDir(binDir, ".klardrop-extract")

    onProgress(null) // Indeterminate progress

    try {
      // 1. Download to a temp file in the same filesystem as the target
      if (downloader != null) {
        downloader.invoke(asset.url, tempTarball, onProgress)
      } else {
        val curlResult = execProcess(
          argv = listOf("curl", "-fsSL", "--proto", "=https", "--proto-redir", "=https", "--max-time", "600", "-o", tempTarball, asset.url),
          timeoutMillis = 600_000L,
        )
        if (curlResult.exitCode != 0) {
          throw IllegalStateException("curl download failed (exit ${curlResult.exitCode}): ${curlResult.stderrString}")
        }
      }

      // 2. Verify sha256 by streaming chunks
      val actualSha = computeFileSha256(tempTarball)
      if (actualSha != expectedSha) {
        throw IllegalStateException("checksum mismatch (expected $expectedSha, got $actualSha)")
      }

      // 3. Security check on tar entries before extraction
      val listResult = execProcess(listOf("tar", "-tvzf", tempTarball))
      if (listResult.exitCode != 0) {
        throw IllegalStateException("tar listing failed (exit ${listResult.exitCode}): ${listResult.stderrString}")
      }
      validateTarEntries(listResult.stdoutString.lines())

      // 4. Extract with tar into staging dir next to target
      val extractResult = execProcess(listOf("tar", "-xzf", tempTarball, "--no-same-owner", "--no-same-permissions", "-C", extractDir))
      if (extractResult.exitCode != 0) {
        throw IllegalStateException("tar extraction failed (exit ${extractResult.exitCode}): ${extractResult.stderrString}")
      }

      // 5. Verify expected layout. The top-level dir tracks the arch; the manifest only
      // serves this build's own arch (see platformUpdateAssetKey), so expect exactly that.
      // BOTH binaries are mandatory. An engine without its CLI — or a CLI without its
      // engine — is a broken install, so the update is rejected here instead of
      // half-applied later.
      val tarballDir =
        if (Platform.cpuArchitecture == CpuArchitecture.ARM64) "klardrop-native-linux-arm64"
        else "klardrop-native-linux-x64"
      val extractRoot = "$extractDir/$tarballDir"
      val pluginManifest = "$extractRoot/share/klardrop/omarchy-plugin/manifest.json"

      for (slot in binarySlots()) {
        requireExecutableBinary("$extractRoot/${slot.relative}", slot.relative)
      }
      if (hasPluginInstalled) {
        if (!isRegularFile(pluginManifest)) {
          throw IllegalStateException("Unexpected tarball layout: missing share/klardrop/omarchy-plugin/manifest.json")
        }
      }
      if (flavor == LinuxFlavor.QT) {
        requireExecutableBinary("$extractRoot/bin/klardrop-qt", "bin/klardrop-qt")
        requireExecutableBinary("$extractRoot/bin/klardrop-qt-launcher", "bin/klardrop-qt-launcher")
      }

      // 6. Stage next to target. Both binaries are copied out of the SAME extracted
      // tree, so the staged pair can only ever be the pair this release shipped.
      rmRf(stagedDir, stagedEngine, stagedClient, stagedQtBin, stagedQtLauncher)
      val treeRoot = if (hasPluginInstalled || flavor == LinuxFlavor.QT) {
        val mvResult = execProcess(listOf("mv", extractRoot, stagedDir))
        if (mvResult.exitCode != 0) {
          throw IllegalStateException("Failed to stage extracted tree (exit ${mvResult.exitCode})")
        }
        stagedDir
      } else {
        extractRoot
      }
      for (slot in binarySlots()) {
        val cpResult = execProcess(listOf("cp", "-p", "$treeRoot/${slot.relative}", slot.staged))
        if (cpResult.exitCode != 0) {
          throw IllegalStateException("Failed to stage ${slot.label} binary (exit ${cpResult.exitCode})")
        }
        chmod(slot.staged, execMode)
        // A wrong-arch binary is rejected here, while it is still only a staged file
        // — before it can replace a working install with one that cannot start.
        verifyStagedArch(slot)
      }
      if (flavor == LinuxFlavor.QT) {
        val cpQtResult = execProcess(listOf("cp", "-p", "$treeRoot/bin/klardrop-qt", stagedQtBin))
        if (cpQtResult.exitCode != 0) {
          throw IllegalStateException("Failed to stage Qt binary (exit ${cpQtResult.exitCode})")
        }
        val cpLauncherResult = execProcess(listOf("cp", "-p", "$treeRoot/bin/klardrop-qt-launcher", stagedQtLauncher))
        if (cpLauncherResult.exitCode != 0) {
          throw IllegalStateException("Failed to stage Qt launcher (exit ${cpLauncherResult.exitCode})")
        }
        chmod(stagedQtBin, execMode)
        chmod(stagedQtLauncher, execMode)
      }
    } catch (e: Throwable) {
      runCatching { rmRf(stagedDir, stagedEngine, stagedClient, stagedQtBin, stagedQtLauncher) }
      throw e
    } finally {
      if (fileExists(tempTarball)) unlink(tempTarball)
      if (isDirectory(extractDir)) runCatching { rmRf(extractDir) }
    }
  }

  override fun applyAndRestart() {
    requireCurrentQtMarker("apply")
    val targetPlugin = "$homeDir/.config/omarchy/plugins/klardrop.omarchy"
    val hasPluginInstalled = isDirectory(targetPlugin)

    // 1. Validate staged files BEFORE mutation. Both halves of the pair must be
    // staged: applying only one would leave a client and an engine from different
    // releases, which is the exact failure this updater exists to avoid.
    validateStagedBinaries()
    if (hasPluginInstalled) {
      val srcPlugin = "$stagedDir/share/klardrop/omarchy-plugin"
      val srcManifest = "$srcPlugin/manifest.json"
      if (!isDirectory(stagedDir) || !isDirectory(srcPlugin) || !isRegularFile(srcManifest)) {
        throw IllegalStateException("Staged update missing or incomplete plugin tree: $srcManifest")
      }
    }
    if (flavor == LinuxFlavor.QT) {
      if (!isRegularFile(stagedQtBin) || !isExecutable(stagedQtBin)) {
        throw IllegalStateException("Staged update missing or incomplete Qt binary ($stagedQtBin)")
      }
      if (!isRegularFile(stagedQtLauncher) || !isExecutable(stagedQtLauncher)) {
        throw IllegalStateException("Staged update missing or incomplete Qt launcher ($stagedQtLauncher)")
      }
      // Validate Qt runtime offscreen before any target mutation
      val checkResult = execProcess(listOf(stagedQtBin, "--check-runtime"), timeoutMillis = 10_000L)
      if (checkResult.exitCode != 0) {
        val err = checkResult.stderrString.ifBlank { checkResult.stdoutString }.trim().take(500)
        throw IllegalStateException("Staged Qt binary failed runtime check (exit ${checkResult.exitCode}): $err")
      }
    }

    // 2. Validate target paths (package-managed files, symlinks, non-regular files)
    // BEFORE mutation — re-checked at apply time because a package manager or a
    // second install may have claimed the files while the download was running.
    requireReplaceableTargets("apply")

    if (flavor == LinuxFlavor.QT) {
      if (isPackageManaged(targetQtBin)) {
        throw IllegalStateException("Refusing to overwrite package-managed binary: $targetQtBin")
      }
      if (pathExistsLstat(targetQtBin)) {
        if (isSymlink(targetQtBin)) {
          throw IllegalStateException("Target Qt binary is a symlink: $targetQtBin")
        }
        if (!isRegularFile(targetQtBin)) {
          throw IllegalStateException("Target Qt binary is not a regular file: $targetQtBin")
        }
      }
      if (isPackageManaged(targetQtLauncher)) {
        throw IllegalStateException("Refusing to overwrite package-managed binary: $targetQtLauncher")
      }
      if (pathExistsLstat(targetQtLauncher)) {
        if (isSymlink(targetQtLauncher)) {
          throw IllegalStateException("Target Qt launcher is a symlink: $targetQtLauncher")
        }
        if (!isRegularFile(targetQtLauncher)) {
          throw IllegalStateException("Target Qt launcher is not a regular file: $targetQtLauncher")
        }
      }
      val targetDesktop = "$effectiveXdgDataHome/applications/klardrop.desktop"
      if (isPackageManaged(targetDesktop)) {
        throw IllegalStateException("Refusing to overwrite package-managed file: $targetDesktop")
      }
      if (pathExistsLstat(targetDesktop)) {
        if (isSymlink(targetDesktop)) {
          throw IllegalStateException("Target desktop file is a symlink: $targetDesktop")
        }
      }
    }

    for (slot in binarySlots()) {
      chmod(slot.staged, execMode)
    }
    if (flavor == LinuxFlavor.QT) {
      chmod(stagedQtBin, execMode)
      chmod(stagedQtLauncher, execMode)
    }

    // The swap covers the engine, the CLI and (for a Qt install) the Qt pair, in
    // that order, as one list: the pair is backed up together, replaced back to
    // back, and rolled back together.
    val units = mutableListOf(
      ReplaceUnit("engine binary", targetEngine, stagedEngine, ".klardrop-engine.bak"),
      ReplaceUnit("client binary", targetClient, stagedClient, ".klardrop.bak"),
    )
    if (flavor == LinuxFlavor.QT) {
      units += ReplaceUnit("Qt binary", targetQtBin, stagedQtBin, ".klardrop-qt.bak")
      units += ReplaceUnit("Qt launcher", targetQtLauncher, stagedQtLauncher, ".klardrop-qt-launcher.bak")
    }

    // Take EVERY backup before replacing anything, so a failure while backing up
    // leaves the install exactly as it was instead of half replaced.
    for (unit in units) {
      unit.hadOld = pathExistsLstat(unit.target)
      if (!unit.hadOld) continue
      val b = makeTempFile(binDir, unit.backupPrefix)
      unlink(b)
      if (link(unit.target, b) != 0) {
        for (taken in units) {
          val backup = taken.backup ?: continue
          if (fileExists(backup)) unlink(backup)
        }
        throw IllegalStateException(
          "Failed to link target ${unit.label} to backup (errno ${posix_errno()})"
        )
      }
      unit.backup = b
    }

    try {
      // Renames are adjacent and cannot fail halfway through the pair in practice;
      // if one ever does, the catch block puts BOTH back.
      for (unit in units) {
        if (renameFile(unit.staged, unit.target) != 0) {
          throw IllegalStateException(
            "Failed to rename new ${unit.label} to ${unit.target} (errno ${posix_errno()})"
          )
        }
        unit.replaced = true
      }

      if (flavor == LinuxFlavor.QT) {
        // Do NOT overwrite user desktop entry (preserve absolute Exec path from installer).
        // Update icons best-effort under configured XDG location, skipping linked/nonregular/package-managed targets.
        runCatching {
          for (s in listOf("128", "256")) {
            val srcIcon = "$stagedDir/share/klardrop/icons/${s}x${s}/klardrop.png"
            val iconDir = "$effectiveXdgDataHome/icons/hicolor/${s}x${s}/apps"
            val targetIcon = "$iconDir/klardrop.png"
            updateIconSafe(srcIcon, iconDir, targetIcon)
          }
          execProcess(listOf("gtk-update-icon-cache", "-qtf", "$effectiveXdgDataHome/icons/hicolor"))
        }
      }

      if (hasPluginInstalled) {
        var stagedPlugin: String? = null
        var backupPlugin: String? = null
        try {
          // Replace plugin dir via a staged dir + rename (with rollback)
          val pluginParent = "$homeDir/.config/omarchy/plugins"
          mkdirs(pluginParent)
          stagedPlugin = makeTempDir(pluginParent, ".klardrop.omarchy.staged").also { rmdir(it) }
          backupPlugin = makeTempDir(pluginParent, ".klardrop.omarchy.bak").also { rmdir(it) }

          val srcPlugin = "$stagedDir/share/klardrop/omarchy-plugin"
          val cpResult = execProcess(listOf("cp", "-a", srcPlugin, stagedPlugin))
          if (cpResult.exitCode != 0) {
            val stderr = cpResult.stderrString.trim().take(500)
            throw IllegalStateException("Failed to copy plugin to staging (exit ${cpResult.exitCode}): $stderr")
          }

          val hadPlugin = isDirectory(targetPlugin)
          if (hadPlugin) {
            if (rename(targetPlugin, backupPlugin) != 0) {
              throw IllegalStateException("Failed to backup target plugin (errno ${posix_errno()})")
            }
          }

          if (rename(stagedPlugin, targetPlugin) != 0) {
            val pluginErrno = posix_errno()
            if (hadPlugin && isDirectory(backupPlugin)) {
              if (rename(backupPlugin, targetPlugin) != 0) {
                val rbErrno = posix_errno()
                throw IllegalStateException("Failed to rename staged plugin to target (errno $pluginErrno); rollback failed (errno $rbErrno)")
              }
            }
            throw IllegalStateException("Failed to rename staged plugin to target (errno $pluginErrno)")
          }

          if (hadPlugin && isDirectory(backupPlugin)) {
            runCatching { rmRf(backupPlugin) }
          }
        } catch (e: Throwable) {
          stagedPlugin?.let { if (isDirectory(it)) runCatching { rmRf(it) } }
          throw e
        }

        // Best-effort plugin rescan (item 11)
        runCatching {
          execProcess(listOf("omarchy-shell", "shell", "rescanPlugins"), timeoutMillis = 5_000L)
        }

        // Update icons (best-effort)
        runCatching {
          for (s in listOf("128", "256")) {
            val srcIcon = "$stagedDir/share/klardrop/icons/${s}x${s}/klardrop.png"
            val iconDir = "$effectiveXdgDataHome/icons/hicolor/${s}x${s}/apps"
            val targetIcon = "$iconDir/klardrop.png"
            updateIconSafe(srcIcon, iconDir, targetIcon)
          }
          execProcess(listOf("gtk-update-icon-cache", "-qtf", "$effectiveXdgDataHome/icons/hicolor"))
        }
      }
    } catch (e: Throwable) {
      val rollbackFailures = mutableListOf<String>()

      // Roll back in reverse replacement order: the newest file goes first, so an
      // install never passes through a state where only one binary of the pair is
      // the new version.
      for (unit in units.asReversed()) {
        if (!unit.replaced) continue
        renameFile(unit.target, unit.staged)
        val backup = unit.backup
        if (unit.hadOld && backup != null && fileExists(backup)) {
          if (renameFile(backup, unit.target) != 0) {
            rollbackFailures.add("Failed to restore ${unit.target} from $backup (errno ${posix_errno()})")
          }
        } else if (!unit.hadOld) {
          if (pathExistsLstat(unit.target)) unlink(unit.target)
        }
      }

      // Clean up ONLY unconsumed backups for targets that were NEVER replaced
      // (where the target was untouched and the backup is merely an extra hard
      // link). A backup whose restore FAILED is deliberately preserved: it is the
      // only copy of that file left, and the message below names it.
      for (unit in units) {
        if (unit.replaced) continue
        val backup = unit.backup ?: continue
        if (fileExists(backup)) unlink(backup)
      }

      if (rollbackFailures.isNotEmpty()) {
        throw IllegalStateException(
          "Update failed: ${e.message}\n" +
            "Rollback errors occurred (backups preserved for manual recovery):\n" +
            rollbackFailures.joinToString("\n") +
            "\nActionable recovery: manually restore binaries using the preserved backup paths above."
        )
      }
      throw e
    }

    // Backup cleanup on successful commit
    for (unit in units) {
      val backup = unit.backup ?: continue
      if (fileExists(backup)) unlink(backup)
    }

    // Clean up staged tree (best-effort)
    if (isDirectory(stagedDir)) {
      runCatching { rmRf(stagedDir) }
    }

    if (!isSystemdManaged()) {
      throw IllegalStateException("Update installed; restart Klardrop manually")
    }

    // Restart daemon via Exec
    val restartResult = execProcess(restartCommand, timeoutMillis = 10_000L)
    if (restartResult.exitCode != 0) {
      throw IllegalStateException("restart command failed (exit ${restartResult.exitCode}): ${restartResult.stderrString.ifBlank { "unknown error" }}")
    }
  }
}
