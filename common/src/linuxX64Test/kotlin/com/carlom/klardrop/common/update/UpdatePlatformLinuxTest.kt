package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.execProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.posix.*
import kotlin.test.*
import kotlin.time.TimeSource

@OptIn(ExperimentalForeignApi::class)
class UpdatePlatformLinuxTest {

  private val mode0755: UInt = (S_IRWXU or S_IRGRP or S_IXGRP or S_IROTH or S_IXOTH).toUInt()

  @Test
  fun testPlatformUpdateAssetKeyIsLinuxNativeX64() {
    assertEquals(UpdateChecker.ASSET_LINUX_NATIVE_X64, platformUpdateAssetKey)
    assertEquals("linux-native-x64", platformUpdateAssetKey)
  }

  @Test
  fun testDetectInstallChannelInjectedNullNeverFallsThrough() {
    val fakeHome = "/home/testuser"
    var checkerCalled = false
    val channel = detectLinuxInstallChannel(
      exePath = "/usr/bin/klardrop",
      home = fakeHome,
      packageChecker = {
        checkerCalled = true
        null
      },
    )
    assertTrue(checkerCalled)
    assertEquals(InstallChannel.MANUAL, channel)
  }

  @Test
  fun testDetectInstallChannelPackageManaged() {
    val fakeHome = "/home/testuser"
    assertEquals(
      InstallChannel.PACMAN,
      detectLinuxInstallChannel(
        exePath = "/usr/bin/klardrop",
        home = fakeHome,
        packageChecker = { InstallChannel.PACMAN },
      ),
    )
    assertEquals(
      InstallChannel.DEB,
      detectLinuxInstallChannel(
        exePath = "/usr/bin/klardrop",
        home = fakeHome,
        packageChecker = { InstallChannel.DEB },
      ),
    )
  }

  @Test
  fun testDetectInstallChannelDevPathIsManual() {
    // In test runner, current executable is test.kexe (under build/bin/linuxX64/...)
    val channel = detectInstallChannel()
    assertEquals(InstallChannel.MANUAL, channel)

    val fakeHome = "/home/testuser"
    // `packageChecker = { null }` everywhere: the default checker shells out to the real
    // pacman/dpkg/rpm, so on a machine where `klardrop-native-bin` owns
    // /usr/bin/klardrop this would return PACMAN for an environmental reason.
    assertEquals(
      InstallChannel.MANUAL,
      detectLinuxInstallChannel(exePath = "/tmp/build/bin/klardrop", home = fakeHome, packageChecker = { null }),
    )
    assertEquals(
      InstallChannel.MANUAL,
      detectLinuxInstallChannel(exePath = "/usr/bin/klardrop", home = fakeHome, packageChecker = { null }),
    )
    assertEquals(
      InstallChannel.MANUAL,
      detectLinuxInstallChannel(exePath = null, home = fakeHome, packageChecker = { null }),
    )
    assertEquals(
      InstallChannel.MANUAL,
      detectLinuxInstallChannel(exePath = "$fakeHome/.local/bin/klardrop", home = null, packageChecker = { null }),
    )
  }

  @Test
  fun testDetectInstallChannelInstalledPathIsTarball() {
    val fakeHome = "/home/testuser"
    assertEquals(
      InstallChannel.TARBALL,
      detectLinuxInstallChannel(
        exePath = "$fakeHome/.local/bin/klardrop",
        home = fakeHome,
        packageChecker = { null },
      ),
    )
  }

  @Test
  fun testPathTraversalTarballRefusal() {
    val exTraverse1 = assertFailsWith<SecurityException> {
      validateTarEntries(listOf("-rw-r--r-- u/g 4 2026-09-26 12:00 ../evil.sh"))
    }
    assertTrue(exTraverse1.message?.contains("path traversal segment '..'") == true, "Expected traversal error, got: ${exTraverse1.message}")

    val exTraverse2 = assertFailsWith<SecurityException> {
      validateTarEntries(listOf("-rw-r--r-- u/g 4 2026-09-26 12:00 klardrop-native-linux-x64/../../escape.sh"))
    }
    assertTrue(exTraverse2.message?.contains("path traversal segment '..'") == true, "Expected traversal error, got: ${exTraverse2.message}")

    val exTraverse3 = assertFailsWith<SecurityException> {
      validateTarEntries(listOf("-rw-r--r-- u/g 4 2026-09-26 12:00 a/../../x"))
    }
    assertTrue(exTraverse3.message?.contains("path traversal segment '..'") == true, "Expected traversal error, got: ${exTraverse3.message}")

    val exTraverse4 = assertFailsWith<SecurityException> {
      validateTarEntries(listOf("-rw-r--r-- u/g 4 2026-09-26 12:00 .."))
    }
    assertTrue(exTraverse4.message?.contains("path traversal segment '..'") == true, "Expected traversal error, got: ${exTraverse4.message}")

    val exAbs1 = assertFailsWith<SecurityException> {
      validateTarEntries(listOf("-rw-r--r-- u/g 4 2026-09-26 12:00 /etc/x"))
    }
    assertTrue(exAbs1.message?.contains("absolute path") == true, "Expected absolute path error, got: ${exAbs1.message}")

    val exAbs2 = assertFailsWith<SecurityException> {
      validateTarEntries(listOf("-rw-r--r-- u/g 4 2026-09-26 12:00 /absolute/path"))
    }
    assertTrue(exAbs2.message?.contains("absolute path") == true, "Expected absolute path error, got: ${exAbs2.message}")

    val exAbs3 = assertFailsWith<SecurityException> {
      validateTarEntries(listOf("-rw-r--r-- u/g 4 2026-09-26 12:00 \\windows\\path"))
    }
    assertTrue(exAbs3.message?.contains("absolute path") == true, "Expected absolute path error, got: ${exAbs3.message}")
    assertFailsWith<SecurityException> {
      validateTarEntries(listOf("lrwxrwxrwx root/root 0 2026-09-26 12:00 link -> /etc/passwd"))
    }
    assertFailsWith<SecurityException> {
      validateTarEntries(listOf("hrw-r--r-- root/root 0 2026-09-26 12:00 file link to other"))
    }
    assertFailsWith<SecurityException> {
      validateTarEntries(listOf("Crw-r--r-- root/root 0 2026-09-26 12:00 contiguous-file"))
    }
    assertFailsWith<SecurityException> {
      validateTarEntries(listOf("Vrw-r--r-- root/root 0 2026-09-26 12:00 volume-header"))
    }
    assertFailsWith<SecurityException> {
      validateTarEntries(listOf("crw-rw-rw- root/root 0 2026-09-26 12:00 dev/null"))
    }
    assertFailsWith<SecurityException> {
      validateTarEntries(listOf("prw-r--r-- root/root 0 2026-09-26 12:00 myfifo"))
    }

    // Valid entries must not throw
    validateTarEntries(
      listOf(
        "drwxr-xr-x user/group 0 2026-09-26 12:00 klardrop-native-linux-x64/",
        "-rwxr-xr-x user/group 100 2026-09-26 12:00 klardrop-native-linux-x64/bin/klardrop",
      )
    )
  }

  @Test
  fun testSha256MismatchRefusal() = runTest {
    val tempDir = createTempDir("klardrop-test-mismatch")
    try {
      val fakeTarball = "$tempDir/test.tar.gz"
      execProcess(listOf("sh", "-c", "echo 'dummy content' > '$fakeTarball'"))

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempDir,
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
      )

      val badAsset = ReleaseAsset(
        url = "https://example.com/asset.tar.gz",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.downloadAndStage(badAsset) {}
      }
      assertTrue(ex.message?.contains("checksum mismatch") == true, "Expected checksum mismatch message, got: ${ex.message}")

      // Refuse missing sha256
      val noShaAsset = ReleaseAsset(
        url = "https://example.com/asset.tar.gz",
        sha256 = null,
      )
      assertFailsWith<IllegalStateException> {
        installer.downloadAndStage(noShaAsset) {}
      }

      // Refuse non-HTTPS
      val insecureAsset = ReleaseAsset(
        url = "http://example.com/asset.tar.gz",
        sha256 = "0000000000000000000000000000000000000000000000000000000000000000",
      )
      assertFailsWith<SecurityException> {
        installer.downloadAndStage(insecureAsset) {}
      }
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testPathTraversalInTarballDownloadRefused() = runTest {
    val tempDir = createTempDir("klardrop-test-traversal")
    try {
      val evilTarball = "$tempDir/evil.tar.gz"
      val pyScript = """
import tarfile, io
with tarfile.open('$evilTarball', mode='w:gz') as tar:
    ti = tarfile.TarInfo(name='../evil.txt')
    ti.size = 4
    tar.addfile(ti, io.BytesIO(b'evil'))
    ti2 = tarfile.TarInfo(name='klardrop-native-linux-x64/bin/klardrop')
    ti2.size = 4
    ti2.mode = 0o755
    tar.addfile(ti2, io.BytesIO(b'test'))
""".trimIndent()
      execProcess(listOf("python3", "-c", pyScript))
      val sha = computeFileSha256(evilTarball)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempDir,
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", evilTarball, destFile))
        },
      )
      val asset = ReleaseAsset(url = "https://example.com/test.tar.gz", sha256 = sha)

      assertFailsWith<SecurityException> {
        installer.downloadAndStage(asset) {}
      }
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testSuccessfulStageAndApply() = runTest {
    val tempDir = createTempDir("klardrop-test-stage-apply")
    try {
      val tempHome = "$tempDir/home"
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      execProcess(listOf("mkdir", "-p", "$tempHome/.config/omarchy/plugins/klardrop.omarchy"))
      execProcess(listOf("sh", "-c", "echo 'old-binary' > '$tempHome/.local/bin/klardrop'"))
      chmod("$tempHome/.local/bin/klardrop", mode0755)
      execProcess(listOf("sh", "-c", "echo 'old-plugin' > '$tempHome/.config/omarchy/plugins/klardrop.omarchy/manifest.json'"))

      // Build a fake valid tarball matching expected layout
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/omarchy-plugin"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/128x128"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/256x256"))

      val newBin = "$extractRoot/bin/klardrop"
      execProcess(listOf("sh", "-c", "echo 'new-native-binary' > '$newBin'"))
      chmod(newBin, mode0755)

      execProcess(listOf("sh", "-c", "echo 'new-native-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)

      execProcess(listOf("sh", "-c", "echo '{\"id\":\"klardrop.omarchy\",\"version\":\"2.0.0\"}' > '$extractRoot/share/klardrop/omarchy-plugin/manifest.json'"))
      execProcess(listOf("sh", "-c", "echo 'png128' > '$extractRoot/share/klardrop/icons/128x128/klardrop.png'"))
      execProcess(listOf("sh", "-c", "echo 'png256' > '$extractRoot/share/klardrop/icons/256x256/klardrop.png'"))
      execProcess(listOf("sh", "-c", "echo '2.0.0' > '$extractRoot/VERSION'"))

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
        isSystemdManaged = { true },
      )

      var progressReported: Float? = -1f
      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) { fraction ->
        progressReported = fraction
      }

      assertNull(progressReported, "Progress must be reported as null (indeterminate)")

      // Staged files should exist
      val stagedBin = "$tempHome/.local/bin/.klardrop.new"
      val stagedDir = "$tempHome/.local/bin/.klardrop-staged-tree"
      assertTrue(isRegularFile(stagedBin), "Staged binary must exist")
      assertTrue(isDirectory(stagedDir), "Staged tree must exist")

      // Apply
      installer.applyAndRestart()

      // Target binary updated and executable
      val updatedBinContent = execProcess(listOf("cat", "$tempHome/.local/bin/klardrop")).stdoutString.trim()
      assertEquals("new-native-binary", updatedBinContent)
      assertTrue(isExecutable("$tempHome/.local/bin/klardrop"), "Target binary must be executable")

      // The engine is replaced as the other half of the pair — a CLI from this
      // release beside an engine from any other would be the failure to avoid.
      assertEquals(
        "new-native-engine",
        execProcess(listOf("cat", "$tempHome/.local/bin/klardrop-engine")).stdoutString.trim(),
        "Engine must be updated from the same release as the client",
      )
      assertTrue(isExecutable("$tempHome/.local/bin/klardrop-engine"), "Engine must be executable")

      // Plugin updated
      val updatedPlugin = execProcess(listOf("cat", "$tempHome/.config/omarchy/plugins/klardrop.omarchy/manifest.json")).stdoutString.trim()
      assertTrue(updatedPlugin.contains("\"version\":\"2.0.0\""), updatedPlugin)

      // Icons updated
      assertTrue(isRegularFile("$tempHome/.local/share/icons/hicolor/128x128/apps/klardrop.png"))
      assertTrue(isRegularFile("$tempHome/.local/share/icons/hicolor/256x256/apps/klardrop.png"))

      // Restart executed
      val restarted = execProcess(listOf("cat", restartLog)).stdoutString.trim()
      assertEquals("restarted", restarted)

      // Staging files cleaned up
      assertFalse(fileExists(stagedBin), "Staged binary must be cleaned up")
      assertFalse(fileExists(stagedDir), "Staged tree must be cleaned up")
      val binEntries = listDirectory("$tempHome/.local/bin")
      assertTrue(binEntries.none { it.startsWith(".klardrop.bak") }, "Backup binary must be cleaned up, found: $binEntries")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testGenericHeadlessStageAndApplyDoesNotCreateOmarchyDirs() = runTest {
    val tempDir = createTempDir("klardrop-test-headless")
    try {
      val tempHome = "$tempDir/home"
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      execProcess(listOf("sh", "-c", "echo 'old-headless-bin' > '$tempHome/.local/bin/klardrop'"))
      chmod("$tempHome/.local/bin/klardrop", mode0755)

      // Ensure .config/omarchy does NOT exist
      assertFalse(fileExists("$tempHome/.config/omarchy"), "Omarchy dir must not pre-exist")

      // Build a fake valid tarball
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/omarchy-plugin"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/128x128"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/256x256"))

      val newBin = "$extractRoot/bin/klardrop"
      execProcess(listOf("sh", "-c", "echo 'new-headless-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)

      execProcess(listOf("sh", "-c", "echo 'new-headless-bin' > '$newBin'"))
      chmod(newBin, mode0755)
      execProcess(listOf("sh", "-c", "echo '{\"id\":\"klardrop.omarchy\"}' > '$extractRoot/share/klardrop/omarchy-plugin/manifest.json'"))

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
        isSystemdManaged = { true },
      )

      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}

      // Staged binary should exist, but stagedDir should NOT exist for headless generic install!
      val stagedBin = "$tempHome/.local/bin/.klardrop.new"
      val stagedDir = "$tempHome/.local/bin/.klardrop-staged-tree"
      assertTrue(isRegularFile(stagedBin), "Staged binary must exist")
      assertFalse(fileExists(stagedDir), "Staged tree must NOT be retained for generic install")

      installer.applyAndRestart()

      // Target binary updated and executable
      val updatedBinContent = execProcess(listOf("cat", "$tempHome/.local/bin/klardrop")).stdoutString.trim()
      assertEquals("new-headless-bin", updatedBinContent)
      assertTrue(isExecutable("$tempHome/.local/bin/klardrop"), "Target binary must be executable")

      // Omarchy directory must NEVER have been created
      assertFalse(fileExists("$tempHome/.config/omarchy"), "Omarchy config dir must NOT be created for headless")
      assertFalse(fileExists("$tempHome/.local/share/icons"), "Icons dir must NOT be created for headless")

      // Restart executed
      assertEquals("restarted", execProcess(listOf("cat", restartLog)).stdoutString.trim())
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testPackageManagedBinaryRefusesOverwrite() = runTest {
    val tempDir = createTempDir("klardrop-test-pkg-refuse")
    val tempHome = "$tempDir/home"
    try {
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      val stagedBin = "$tempHome/.local/bin/.klardrop.new"
      val stagedEngine = "$tempHome/.local/bin/.klardrop-engine.new"
      execProcess(listOf("sh", "-c", "echo 'new' > '$stagedEngine'"))
      chmod(stagedEngine, mode0755)
      execProcess(listOf("sh", "-c", "echo 'new' > '$stagedBin'"))
      chmod(stagedBin, mode0755)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        isPackageManaged = { true },
      )
      val ex = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(ex.message?.contains("Refusing to overwrite package-managed binary") == true, "Got: ${ex.message}")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testNonHttpsAssetUrlRefusal() = runTest {
    val installer = LinuxNativeTarballInstaller(homeDir = "/tmp")
    assertFailsWith<SecurityException> {
      installer.downloadAndStage(ReleaseAsset("http://example.com/asset.tar.gz", "a".repeat(64))) {}
    }
  }

  @Test
  fun testMissingSha256Refusal() = runTest {
    val installer = LinuxNativeTarballInstaller(homeDir = "/tmp")
    assertFailsWith<IllegalStateException> {
      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", null)) {}
    }
    assertFailsWith<IllegalStateException> {
      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", "   ")) {}
    }
  }

  @Test
  fun testPluginFailureRollsBackBinary() = runTest {
    val tempDir = createTempDir("klardrop-test-plugin-rollback")
    val tempHome = "$tempDir/home"
    try {
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      execProcess(listOf("mkdir", "-p", "$tempHome/.config/omarchy/plugins/klardrop.omarchy"))
      execProcess(listOf("sh", "-c", "echo 'old-binary' > '$tempHome/.local/bin/klardrop'"))
      chmod("$tempHome/.local/bin/klardrop", mode0755)

      // Build a fake valid tarball matching expected layout
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/omarchy-plugin"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/128x128"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/256x256"))

      val newBin = "$extractRoot/bin/klardrop"
      execProcess(listOf("sh", "-c", "echo 'new-native-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)

      execProcess(listOf("sh", "-c", "echo 'new-native-binary' > '$newBin'"))
      chmod(newBin, mode0755)
      execProcess(listOf("sh", "-c", "echo '{\"id\":\"klardrop.omarchy\",\"version\":\"2.0.0\"}' > '$extractRoot/share/klardrop/omarchy-plugin/manifest.json'"))
      execProcess(listOf("sh", "-c", "echo 'png128' > '$extractRoot/share/klardrop/icons/128x128/klardrop.png'"))
      execProcess(listOf("sh", "-c", "echo 'png256' > '$extractRoot/share/klardrop/icons/256x256/klardrop.png'"))

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
        isSystemdManaged = { true },
      )

      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}

      // Make plugin dir read-only so plugin staging fails
      chmod("$tempHome/.config/omarchy/plugins", 0x155u) // 0555

      assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }

      // Old binary must have been rolled back
      val currentBin = execProcess(listOf("cat", "$tempHome/.local/bin/klardrop")).stdoutString.trim()
      assertEquals("old-binary", currentBin, "Binary must be rolled back on plugin failure")

      // Daemon should NOT have restarted
      assertFalse(fileExists(restartLog), "Restart must not be executed when apply fails")
    } finally {
      // Restore permissions for cleanup
      chmod("$tempHome/.config/omarchy/plugins", mode0755)
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testPathSafetyChecks() {
    assertFailsWith<IllegalArgumentException> {
      LinuxNativeTarballInstaller(homeDir = "relative/path")
    }
    assertFailsWith<IllegalArgumentException> {
      LinuxNativeTarballInstaller(homeDir = "")
    }
    assertFailsWith<IllegalArgumentException> {
      LinuxNativeTarballInstaller(homeDir = "/")
    }
  }

  @Test
  fun testNotUnderSystemdThrowsManualRestartRequired() = runTest {
    val tempDir = createTempDir("klardrop-test-no-systemd")
    val tempHome = "$tempDir/home"
    try {
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      val stagedBin = "$tempHome/.local/bin/.klardrop.new"
      val stagedDir = "$tempHome/.local/bin/.klardrop-staged-tree"
      execProcess(listOf("mkdir", "-p", stagedDir))
      val stagedEngine = "$tempHome/.local/bin/.klardrop-engine.new"
      execProcess(listOf("sh", "-c", "echo 'new' > '$stagedEngine'"))
      chmod(stagedEngine, mode0755)
      execProcess(listOf("sh", "-c", "echo 'new' > '$stagedBin'"))
      chmod(stagedBin, mode0755)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        isSystemdManaged = { false },
      )
      val ex = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertEquals("Update installed; restart Klardrop manually", ex.message)
      val installed = execProcess(listOf("cat", "$tempHome/.local/bin/klardrop")).stdoutString.trim()
      assertEquals("new", installed)
      assertTrue(isExecutable("$tempHome/.local/bin/klardrop"))
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testStaleFilesSweptAtDownloadStart() = runTest {
    val tempDir = createTempDir("klardrop-test-stale-sweep")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val staleBak = "$binDir/.klardrop.bak123"
      val staleDownload = "$binDir/.klardrop-download.xyz"
      val staleExtract = "$binDir/.klardrop-extract.abc"
      val keepFile = "$binDir/other-file"
      execProcess(listOf("sh", "-c", "echo bak > '$staleBak'"))
      execProcess(listOf("sh", "-c", "echo dl > '$staleDownload'"))
      execProcess(listOf("mkdir", "-p", staleExtract))
      execProcess(listOf("sh", "-c", "echo other > '$keepFile'"))

      val pluginsDir = "$tempHome/.config/omarchy/plugins"
      execProcess(listOf("mkdir", "-p", pluginsDir))
      val stalePluginStaged = "$pluginsDir/.klardrop.omarchy.staged.123"
      val stalePluginBak = "$pluginsDir/.klardrop.omarchy.bak.456"
      val keepPlugin = "$pluginsDir/klardrop.omarchy"
      val keepOtherPlugin = "$pluginsDir/other.omarchy"
      execProcess(listOf("mkdir", "-p", stalePluginStaged))
      execProcess(listOf("mkdir", "-p", stalePluginBak))
      execProcess(listOf("mkdir", "-p", keepPlugin))
      execProcess(listOf("mkdir", "-p", keepOtherPlugin))

      val fakeTarball = "$tempDir/test.tar.gz"
      execProcess(listOf("sh", "-c", "echo 'dummy' > '$fakeTarball'"))

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
      )
      // downloadAndStage will fail at sha256 check, but stale sweep runs first
      runCatching {
        installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", "0".repeat(64))) {}
      }

      assertTrue(fileExists(staleBak), "failed update backups must NOT be swept on next download")
      assertFalse(fileExists(staleDownload), "stale .klardrop-download.* must be swept")
      assertFalse(fileExists(staleExtract), "stale .klardrop-extract.* must be swept")
      assertTrue(fileExists(keepFile), "unrelated files must not be touched")
      assertFalse(fileExists(stalePluginStaged), "stale .klardrop.omarchy.staged.* must be swept")
      assertTrue(fileExists(stalePluginBak), "failed plugin backups must NOT be swept on next download")
      assertTrue(isDirectory(keepPlugin), "active plugin must not be touched")
      assertTrue(isDirectory(keepOtherPlugin), "unrelated plugin must not be touched")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testGenuineBinOnlyHeadlessTarballNoPluginNoOmarchyDir() = runTest {
    val tempDir = createTempDir("klardrop-test-headless-binonly")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      execProcess(listOf("sh", "-c", "echo 'old-binary' > '$binDir/klardrop'"))

      // Genuine bin-only stage (no share/ directory, no plugin)
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$extractRoot/bin/klardrop'"))
      chmod("$extractRoot/bin/klardrop", mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
        isSystemdManaged = { true },
      )

      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}

      // Staged binary exists, but stagedDir does not
      val stagedBin = "$binDir/.klardrop.new"
      val stagedDir = "$binDir/.klardrop-staged-tree"
      assertTrue(isRegularFile(stagedBin), "Staged binary must exist")
      assertFalse(fileExists(stagedDir), "Staged plugin tree must NOT exist for headless tarball")

      installer.applyAndRestart()

      val updatedBin = execProcess(listOf("cat", "$binDir/klardrop")).stdoutString.trim()
      assertEquals("new-binary", updatedBin)
      assertTrue(fileExists(restartLog), "Daemon should have restarted")
      assertFalse(isDirectory("$tempHome/.config/omarchy"), "~/.config/omarchy must NOT be created")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testStaleStagedDirBeforeHeadlessStageRemoved() = runTest {
    val tempDir = createTempDir("klardrop-test-stale-staged-dir")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val staleStagedDir = "$binDir/.klardrop-staged-tree"
      execProcess(listOf("mkdir", "-p", "$staleStagedDir/old"))
      execProcess(listOf("sh", "-c", "echo old > '$staleStagedDir/old/file'"))

      // Genuine bin-only stage
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$extractRoot/bin/klardrop'"))
      chmod("$extractRoot/bin/klardrop", mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
      )

      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}

      assertFalse(fileExists(staleStagedDir), "Stale stagedDir must be removed even when no plugin is installed")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testIncompletePluginTreeFailsBeforeBinaryMutationAndPreservesOldPlugin() = runTest {
    val tempDir = createTempDir("klardrop-test-incomplete-plugin")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val targetBin = "$binDir/klardrop"
      execProcess(listOf("sh", "-c", "echo 'old-binary' > '$targetBin'"))

      val targetPlugin = "$tempHome/.config/omarchy/plugins/klardrop.omarchy"
      execProcess(listOf("mkdir", "-p", targetPlugin))
      execProcess(listOf("sh", "-c", "echo 'old-plugin' > '$targetPlugin/manifest.json'"))

      val stagedBin = "$binDir/.klardrop.new"
      val stagedDir = "$binDir/.klardrop-staged-tree"
      execProcess(listOf("mkdir", "-p", "$stagedDir/share/klardrop/omarchy-plugin"))
      // manifest.json is missing in stagedDir!
      val stagedEngine = "$binDir/.klardrop-engine.new"
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$stagedEngine'"))
      chmod(stagedEngine, mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$stagedBin'"))
      chmod(stagedBin, mode0755)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        isSystemdManaged = { true },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(ex.message?.contains("missing or incomplete plugin tree") == true, "Expected plugin tree error, got: ${ex.message}")

      val currentBin = execProcess(listOf("cat", targetBin)).stdoutString.trim()
      assertEquals("old-binary", currentBin, "Binary must NOT be replaced if staged plugin is incomplete")

      val currentPlugin = execProcess(listOf("cat", "$targetPlugin/manifest.json")).stdoutString.trim()
      assertEquals("old-plugin", currentPlugin, "Original plugin must be preserved")

      assertFalse(fileExists(restartLog), "Daemon must not restart")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testPackageRefusalLeavesExistingBinaryUnchanged() = runTest {
    val tempDir = createTempDir("klardrop-test-package-refusal")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val targetBin = "$binDir/klardrop"
      execProcess(listOf("sh", "-c", "echo 'package-binary' > '$targetBin'"))

      val stagedBin = "$binDir/.klardrop.new"
      val stagedEngine = "$binDir/.klardrop-engine.new"
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$stagedEngine'"))
      chmod(stagedEngine, mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-binary' > '$stagedBin'"))
      chmod(stagedBin, mode0755)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        isPackageManaged = { true },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(ex.message?.contains("Refusing to overwrite package-managed binary") == true)

      val currentBin = execProcess(listOf("cat", targetBin)).stdoutString.trim()
      assertEquals("package-binary", currentBin, "Existing package-managed binary must be unchanged")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testReadMarkerFileSafeValidation() {
    val tempDir = createTempDir("klardrop-test-marker-read")
    try {
      // Valid single-line flavors with optional trailing newline
      val qtFile = "$tempDir/marker-qt"
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$qtFile'"))
      assertEquals("qt", readMarkerFileSafe(qtFile))

      val omarchyFile = "$tempDir/marker-omarchy"
      execProcess(listOf("sh", "-c", "printf 'omarchy' > '$omarchyFile'"))
      assertEquals("omarchy", readMarkerFileSafe(omarchyFile))

      val nativeFile = "$tempDir/marker-native"
      execProcess(listOf("sh", "-c", "printf 'native\\r\\n' > '$nativeFile'"))
      assertEquals("native", readMarkerFileSafe(nativeFile))

      // Legacy 0-byte marker returns empty string
      val emptyFile = "$tempDir/marker-empty"
      execProcess(listOf("sh", "-c", "> '$emptyFile'"))
      assertEquals("", readMarkerFileSafe(emptyFile))

      // Symlink rejected
      val symlinkFile = "$tempDir/marker-symlink"
      execProcess(listOf("ln", "-s", qtFile, symlinkFile))
      assertNull(readMarkerFileSafe(symlinkFile), "Symlink marker must be rejected")

      // FIFO rejected without blocking
      val fifoFile = "$tempDir/marker-fifo"
      execProcess(listOf("mkfifo", fifoFile))
      assertNull(readMarkerFileSafe(fifoFile), "FIFO marker must be rejected without blocking")

      // Oversize (>64 bytes) rejected
      val oversizeFile = "$tempDir/marker-oversize"
      execProcess(listOf("sh", "-c", "printf '%070d' 1 > '$oversizeFile'"))
      assertNull(readMarkerFileSafe(oversizeFile), "Oversize marker must be rejected")

      // Embedded NUL byte rejected
      val nulFile = "$tempDir/marker-nul"
      execProcess(listOf("sh", "-c", "printf 'qt\\0extra' > '$nulFile'"))
      assertNull(readMarkerFileSafe(nulFile), "Marker with NUL byte must be rejected")

      // Multiple lines rejected
      val multiLineFile = "$tempDir/marker-multiline"
      execProcess(listOf("sh", "-c", "printf 'qt\\nextra\\n' > '$multiLineFile'"))
      assertNull(readMarkerFileSafe(multiLineFile), "Marker with multiple lines must be rejected")

      // Chained LF CRLF rejected
      val lfCrlfFile = "$tempDir/marker-lf-crlf"
      execProcess(listOf("sh", "-c", "printf 'qt\\n\\r\\n' > '$lfCrlfFile'"))
      assertNull(readMarkerFileSafe(lfCrlfFile), "qt with chained LF CRLF must be rejected")

      // Invalid/unknown flavor rejected
      val invalidFile = "$tempDir/marker-invalid"
      execProcess(listOf("sh", "-c", "printf 'other\\n' > '$invalidFile'"))
      assertNull(readMarkerFileSafe(invalidFile), "Unknown flavor marker must be rejected")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testDetectLinuxFlavorQtOnlyWithExplicitMarker() {
    val tempDir = createTempDir("klardrop-test-detect-flavor")
    val tempHome = "$tempDir/home"
    try {
      val dataHome = "$tempHome/.local/share"
      val markerDir = "$dataHome/klardrop"
      execProcess(listOf("mkdir", "-p", markerDir))
      val markerFile = "$markerDir/.installer-marker"

      // 1. Explicit qt marker -> QT
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$markerFile'"))
      assertEquals(LinuxFlavor.QT, detectLinuxFlavor(home = tempHome, xdgDataHome = dataHome))

      // 2. Explicit omarchy marker -> OMARCHY
      execProcess(listOf("sh", "-c", "printf 'omarchy\\n' > '$markerFile'"))
      assertEquals(LinuxFlavor.OMARCHY, detectLinuxFlavor(home = tempHome, xdgDataHome = dataHome))

      // 3. Explicit native marker -> NATIVE
      execProcess(listOf("sh", "-c", "printf 'native\\n' > '$markerFile'"))
      assertEquals(LinuxFlavor.NATIVE, detectLinuxFlavor(home = tempHome, xdgDataHome = dataHome))

      // 4. Manual GUI binary present without marker -> NEVER infers QT (returns NATIVE)
      execProcess(listOf("rm", "-f", markerFile))
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val manualGui = "$binDir/klardrop-qt"
      execProcess(listOf("sh", "-c", "echo 'manual-gui' > '$manualGui'"))
      assertEquals(LinuxFlavor.NATIVE, detectLinuxFlavor(home = tempHome, xdgDataHome = dataHome), "Bare klardrop-qt without marker must NOT infer QT")

      // 5. Legacy empty marker with Omarchy plugin present -> OMARCHY
      execProcess(listOf("sh", "-c", "> '$markerFile'"))
      val pluginDir = "$tempHome/.config/omarchy/plugins/klardrop.omarchy"
      execProcess(listOf("mkdir", "-p", pluginDir))
      assertEquals(LinuxFlavor.OMARCHY, detectLinuxFlavor(home = tempHome, xdgDataHome = dataHome))

      // 6. Legacy empty marker without Omarchy plugin -> NATIVE (never QT even with manual GUI)
      execProcess(listOf("rm", "-rf", pluginDir))
      assertEquals(LinuxFlavor.NATIVE, detectLinuxFlavor(home = tempHome, xdgDataHome = dataHome))

      // 7. Custom XDG_DATA_HOME respected
      val customDataHome = "$tempDir/custom-data"
      val customMarkerDir = "$customDataHome/klardrop"
      execProcess(listOf("mkdir", "-p", customMarkerDir))
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$customMarkerDir/.installer-marker'"))
      assertEquals(LinuxFlavor.QT, detectLinuxFlavor(home = tempHome, xdgDataHome = customDataHome))
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testSuccessfulEngineAndQtAndLauncherUpdate() = runTest {
    val tempDir = createTempDir("klardrop-test-qt-update")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val dataHome = "$tempHome/.local/share"
      execProcess(listOf("mkdir", "-p", "$dataHome/klardrop"))
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$dataHome/klardrop/.installer-marker'"))

      val targetBin = "$binDir/klardrop"
      val targetQtBin = "$binDir/klardrop-qt"
      val targetQtLauncher = "$binDir/klardrop-qt-launcher"
      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-qt' > '$targetQtBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-launcher' > '$targetQtLauncher'"))
      chmod(targetBin, mode0755)
      chmod(targetQtBin, mode0755)
      chmod(targetQtLauncher, mode0755)

      // Package valid tarball with engine, Qt binary (executable script), and Qt launcher
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/128x128"))
      execProcess(listOf("mkdir", "-p", "$extractRoot/share/klardrop/icons/256x256"))

      val newBin = "$extractRoot/bin/klardrop"
      val newQtBin = "$extractRoot/bin/klardrop-qt"
      val newLauncher = "$extractRoot/bin/klardrop-qt-launcher"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$newBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$newQtBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$newLauncher'"))
      chmod(newBin, mode0755)
      chmod(newQtBin, mode0755)
      chmod(newLauncher, mode0755)

      execProcess(listOf("sh", "-c", "echo 'icon128' > '$extractRoot/share/klardrop/icons/128x128/klardrop.png'"))
      execProcess(listOf("sh", "-c", "echo 'icon256' > '$extractRoot/share/klardrop/icons/256x256/klardrop.png'"))

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
        isSystemdManaged = { true },
      )

      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}
      installer.applyAndRestart()

      assertEquals("new-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim())
      assertTrue(isExecutable(targetBin))
      assertTrue(isExecutable(targetQtBin))
      assertTrue(isExecutable(targetQtLauncher))
      assertTrue(fileExists(restartLog), "Daemon should have restarted")
      assertTrue(fileExists("$dataHome/icons/hicolor/128x128/apps/klardrop.png"))
      assertTrue(fileExists("$dataHome/icons/hicolor/256x256/apps/klardrop.png"))
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testMissingQtOrLauncherPayloadFailsWithoutStageOrTargetChange() = runTest {
    val tempDir = createTempDir("klardrop-test-missing-qt-payload")
    val tempHome = "$tempDir/home"
    try {
      writeValidQtMarker(tempHome)
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val targetBin = "$binDir/klardrop"
      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))

      // Tarball with klardrop engine, but MISSING bin/klardrop-qt
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      val newBin = "$extractRoot/bin/klardrop"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$newBin'"))
      chmod(newBin, mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}
      }
      assertTrue(ex.message?.contains("missing bin/klardrop-qt executable") == true)

      // Target engine untouched, and nothing left staged
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim())
      assertFalse(fileExists("$binDir/.klardrop.new"))
      assertFalse(fileExists("$binDir/.klardrop-qt.new"))
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testManualGuiWithoutMarkerIgnoredAndPreservedHeadless() = runTest {
    val tempDir = createTempDir("klardrop-test-manual-gui-preserved")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val targetBin = "$binDir/klardrop"
      val manualQtBin = "$binDir/klardrop-qt"
      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      execProcess(listOf("sh", "-c", "echo 'custom-user-gui' > '$manualQtBin'"))
      chmod(targetBin, mode0755)
      chmod(manualQtBin, mode0755)

      // Tarball contains both engine and bundled Qt GUI
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      val newBin = "$extractRoot/bin/klardrop"
      val bundledQt = "$extractRoot/bin/klardrop-qt"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)

      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$newBin'"))
      execProcess(listOf("sh", "-c", "echo 'bundled-qt' > '$bundledQt'"))
      chmod(newBin, mode0755)
      chmod(bundledQt, mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.NATIVE, // Headless because no marker
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
        isSystemdManaged = { true },
      )

      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}
      installer.applyAndRestart()

      // Engine updated, but manual GUI preserved completely untouched!
      assertEquals("new-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim())
      assertEquals("custom-user-gui", execProcess(listOf("cat", manualQtBin)).stdoutString.trim(), "Manual GUI must NEVER be overwritten without explicit marker")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testQtPackageOwnershipAndDanglingSymlinkRefusedBeforeEngineChange() = runTest {
    val tempDir = createTempDir("klardrop-test-qt-refusals")
    val tempHome = "$tempDir/home"
    try {
      writeValidQtMarker(tempHome)
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val targetBin = "$binDir/klardrop"
      val targetQtBin = "$binDir/klardrop-qt"
      val targetQtLauncher = "$binDir/klardrop-qt-launcher"

      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      execProcess(listOf("sh", "-c", "echo 'package-qt' > '$targetQtBin'"))
      execProcess(listOf("sh", "-c", "echo 'package-launcher' > '$targetQtLauncher'"))
      chmod(targetBin, mode0755)
      chmod(targetQtBin, mode0755)
      chmod(targetQtLauncher, mode0755)

      // Set up staged files
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$binDir/.klardrop-engine.new'"))
      chmod("$binDir/.klardrop-engine.new", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$binDir/.klardrop.new'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$binDir/.klardrop-qt.new'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$binDir/.klardrop-qt-launcher.new'"))
      chmod("$binDir/.klardrop.new", mode0755)
      chmod("$binDir/.klardrop-qt.new", mode0755)
      chmod("$binDir/.klardrop-qt-launcher.new", mode0755)

      // Case A: Package managed Qt binary refused
      val pkgInstaller = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        isPackageManaged = { it.endsWith("klardrop-qt") },
      )
      val exPkg = assertFailsWith<IllegalStateException> {
        pkgInstaller.applyAndRestart()
      }
      assertTrue(exPkg.message?.contains("Refusing to overwrite package-managed binary") == true)
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim(), "Engine must NOT be modified when Qt package guard trips")

      // Case B: Dangling symlink Qt binary refused
      execProcess(listOf("rm", "-f", targetQtBin))
      execProcess(listOf("ln", "-s", "/nonexistent/path", targetQtBin))
      val symInstaller = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        isPackageManaged = { false },
      )
      val exSym = assertFailsWith<IllegalStateException> {
        symInstaller.applyAndRestart()
      }
      assertTrue(exSym.message?.contains("Target Qt binary is a symlink") == true)
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim(), "Engine must NOT be modified when Qt symlink guard trips")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testForcedQtCommitFailureRestoresEngineAndGuiAndLauncher() = runTest {
    val tempDir = createTempDir("klardrop-test-qt-commit-failure")
    val tempHome = "$tempDir/home"
    try {
      val dataHome = "$tempHome/.local/share"
      writeValidQtMarker(tempHome, dataHome)
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val targetBin = "$binDir/klardrop"
      val targetQtBin = "$binDir/klardrop-qt"
      val targetQtLauncher = "$binDir/klardrop-qt-launcher"

      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-qt' > '$targetQtBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-launcher' > '$targetQtLauncher'"))
      chmod(targetBin, mode0755)
      chmod(targetQtBin, mode0755)
      chmod(targetQtLauncher, mode0755)

      // Set up staged files
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$binDir/.klardrop-engine.new'"))
      chmod("$binDir/.klardrop-engine.new", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$binDir/.klardrop.new'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$binDir/.klardrop-qt.new'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$binDir/.klardrop-qt-launcher.new'"))
      chmod("$binDir/.klardrop.new", mode0755)
      chmod("$binDir/.klardrop-qt.new", mode0755)
      chmod("$binDir/.klardrop-qt-launcher.new", mode0755)

      var engineRenameAttempted = false
      var qtBinRenameAttempted = false
      var launcherRenameAttempted = false
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        xdgDataHome = dataHome,
        isPackageManaged = { false },
        renameFile = { from, to ->
          // Force failure when renaming Qt launcher into place
          if (to.endsWith("klardrop") && from.endsWith(".klardrop.new")) {
            engineRenameAttempted = true
            rename(from, to)
          } else if (to.endsWith("klardrop-qt") && from.endsWith(".klardrop-qt.new")) {
            qtBinRenameAttempted = true
            rename(from, to)
          } else if (to.endsWith("klardrop-qt-launcher") && from.endsWith(".klardrop-qt-launcher.new")) {
            launcherRenameAttempted = true
            -1
          } else {
            rename(from, to)
          }
        },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(engineRenameAttempted, "Engine commit rename must be attempted")
      assertTrue(qtBinRenameAttempted, "Qt binary commit rename must be attempted")
      assertTrue(launcherRenameAttempted, "Launcher commit rename must be attempted")
      assertTrue(
        ex.message?.contains("Failed to rename new Qt launcher") == true,
        "Error must specifically mention failed Qt launcher commit, got: ${ex.message}",
      )

      // Engine, Qt binary, and Qt launcher must all be restored to old versions!
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim(), "Engine must be rolled back on launcher commit failure")
      assertEquals("old-qt", execProcess(listOf("cat", targetQtBin)).stdoutString.trim(), "Qt binary must be rolled back on launcher commit failure")
      assertEquals("old-launcher", execProcess(listOf("cat", targetQtLauncher)).stdoutString.trim(), "Qt launcher must remain old version")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testForcedRestoreFailureRetainsBackupsAndNextDownloadPreserves() = runTest {
    val tempDir = createTempDir("klardrop-test-forced-restore-failure")
    val tempHome = "$tempDir/home"
    try {
      val dataHome = "$tempHome/.local/share"
      writeValidQtMarker(tempHome, dataHome)
      val binDir = "$tempHome/.local/bin"
      execProcess(listOf("mkdir", "-p", binDir))
      val targetBin = "$binDir/klardrop"
      val targetQtBin = "$binDir/klardrop-qt"
      val targetQtLauncher = "$binDir/klardrop-qt-launcher"

      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-qt' > '$targetQtBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-launcher' > '$targetQtLauncher'"))
      chmod(targetBin, mode0755)
      chmod(targetQtBin, mode0755)
      chmod(targetQtLauncher, mode0755)

      // Set up staged files
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$binDir/.klardrop-engine.new'"))
      chmod("$binDir/.klardrop-engine.new", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$binDir/.klardrop.new'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$binDir/.klardrop-qt.new'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$binDir/.klardrop-qt-launcher.new'"))
      chmod("$binDir/.klardrop.new", mode0755)
      chmod("$binDir/.klardrop-qt.new", mode0755)
      chmod("$binDir/.klardrop-qt-launcher.new", mode0755)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        xdgDataHome = dataHome,
        isPackageManaged = { false },
        renameFile = { from, to ->
          // Force Qt binary commit failure, AND force restore of engine backup to fail
          if (to.endsWith("klardrop-qt") && from.endsWith(".klardrop-qt.new")) {
            -1
          } else if (to.endsWith("klardrop") && from.contains(".klardrop.bak")) {
            -1
          } else {
            rename(from, to)
          }
        },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(ex.message?.contains("Rollback errors occurred") == true, "Expected rollback error report, got: ${ex.message}")
      assertTrue(ex.message?.contains("Failed to restore") == true, "Expected failed restore mention, got: ${ex.message}")

      // Verify that backup file STILL EXISTS and was NOT deleted
      val backupsAfterFailure = listDirectory(binDir).filter { it.startsWith(".klardrop.bak") }
      assertEquals(1, backupsAfterFailure.size, "Backup file MUST NOT be deleted when rollback restore fails")
      val backupFile = "$binDir/${backupsAfterFailure.first()}"
      assertEquals("old-engine", execProcess(listOf("cat", backupFile)).stdoutString.trim())

      // Now verify that next download does NOT delete this backup file
      val fakeTarball = "$tempDir/dummy.tar.gz"
      execProcess(listOf("sh", "-c", "echo 'dummy' > '$fakeTarball'"))
      val nextInstaller = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        xdgDataHome = dataHome,
        isPackageManaged = { false },
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
      )
      // downloadAndStage will fail at sha256, but runs stale file sweep first
      runCatching {
        nextInstaller.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", "0".repeat(64))) {}
      }

      assertTrue(fileExists(backupFile), "Next download sweep must NEVER delete failed update backups")
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testMarkerSwitchOrRemovalAfterStagingRefusesApplyLeavingOldFilesIntact() = runTest {
    val tempDir = createTempDir("klardrop-test-marker-switch")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      val dataHome = "$tempHome/.local/share"
      val markerFile = "$dataHome/klardrop/.installer-marker"
      execProcess(listOf("mkdir", "-p", binDir))
      execProcess(listOf("mkdir", "-p", "$dataHome/klardrop"))
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$markerFile'"))

      val targetBin = "$binDir/klardrop"
      val targetQtBin = "$binDir/klardrop-qt"
      val targetQtLauncher = "$binDir/klardrop-qt-launcher"
      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-qt' > '$targetQtBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-launcher' > '$targetQtLauncher'"))
      chmod(targetBin, mode0755)
      chmod(targetQtBin, mode0755)
      chmod(targetQtLauncher, mode0755)

      // Set up staged files
      val stagedBin = "$binDir/.klardrop.new"
      val stagedQtBin = "$binDir/.klardrop-qt.new"
      val stagedQtLauncher = "$binDir/.klardrop-qt-launcher.new"
      val stagedDir = "$binDir/.klardrop-staged-tree"
      execProcess(listOf("mkdir", "-p", stagedDir))
      val stagedEngine = "$binDir/.klardrop-engine.new"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$stagedEngine'"))
      chmod(stagedEngine, mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$stagedBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$stagedQtBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$stagedQtLauncher'"))
      chmod(stagedBin, mode0755)
      chmod(stagedQtBin, mode0755)
      chmod(stagedQtLauncher, mode0755)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        xdgDataHome = dataHome,
        isSystemdManaged = { true },
      )

      // Case A: Marker removed after staging
      unlink(markerFile)
      val exRemoval = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(exRemoval.message?.contains("installer marker is no longer 'qt'") == true, "Got: ${exRemoval.message}")
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim())
      assertEquals("old-qt", execProcess(listOf("cat", targetQtBin)).stdoutString.trim())
      assertEquals("old-launcher", execProcess(listOf("cat", targetQtLauncher)).stdoutString.trim())

      // Case B: Marker switched to 'native' after staging
      execProcess(listOf("sh", "-c", "printf 'native\\n' > '$markerFile'"))
      val exSwitch = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(exSwitch.message?.contains("installer marker is no longer 'qt'") == true, "Got: ${exSwitch.message}")
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim())
      assertEquals("old-qt", execProcess(listOf("cat", targetQtBin)).stdoutString.trim())
      assertEquals("old-launcher", execProcess(listOf("cat", targetQtLauncher)).stdoutString.trim())
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testQtAndOmarchyIconSymlinkAndDanglingReferencesUntouched() = runTest {
    val tempDir = createTempDir("klardrop-test-icon-symlinks")
    val tempHome = "$tempDir/home"
    try {
      // Case A: Standalone Qt
      val binDir = "$tempHome/.local/bin"
      val dataHome = "$tempHome/.local/share"
      execProcess(listOf("mkdir", "-p", binDir))

      // Protected reference file that an existing icon symlink points to
      val protectedFile = "$tempDir/my-protected-ref.png"
      execProcess(listOf("sh", "-c", "echo 'precious-content' > '$protectedFile'"))

      // Set up icons dir: 128 is symlink to protectedFile; 256 is dangling symlink
      val icon128Dir = "$dataHome/icons/hicolor/128x128/apps"
      val icon256Dir = "$dataHome/icons/hicolor/256x256/apps"
      execProcess(listOf("mkdir", "-p", icon128Dir))
      execProcess(listOf("mkdir", "-p", icon256Dir))
      execProcess(listOf("ln", "-s", protectedFile, "$icon128Dir/klardrop.png"))
      execProcess(listOf("ln", "-s", "$tempDir/nonexistent.png", "$icon256Dir/klardrop.png"))

      // Set up staged tree with new icons
      val stagedDir = "$binDir/.klardrop-staged-tree"
      execProcess(listOf("mkdir", "-p", "$stagedDir/share/klardrop/icons/128x128"))
      execProcess(listOf("mkdir", "-p", "$stagedDir/share/klardrop/icons/256x256"))
      execProcess(listOf("sh", "-c", "echo 'new-icon-128' > '$stagedDir/share/klardrop/icons/128x128/klardrop.png'"))
      execProcess(listOf("sh", "-c", "echo 'new-icon-256' > '$stagedDir/share/klardrop/icons/256x256/klardrop.png'"))

      val stagedBin = "$binDir/.klardrop.new"
      val stagedQtBin = "$binDir/.klardrop-qt.new"
      val stagedQtLauncher = "$binDir/.klardrop-qt-launcher.new"
      val stagedEngine = "$binDir/.klardrop-engine.new"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$stagedEngine'"))
      chmod(stagedEngine, mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$stagedBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$stagedQtBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$stagedQtLauncher'"))
      chmod(stagedBin, mode0755)
      chmod(stagedQtBin, mode0755)
      chmod(stagedQtLauncher, mode0755)

      execProcess(listOf("mkdir", "-p", "$dataHome/klardrop"))
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$dataHome/klardrop/.installer-marker'"))

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        xdgDataHome = dataHome,
        isPackageManaged = { false },
        isSystemdManaged = { false },
      )

      val exQt = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertEquals("Update installed; restart Klardrop manually", exQt.message)

      // Assert that protected file referenced by symlink was NOT overwritten
      val refContent = execProcess(listOf("cat", protectedFile)).stdoutString.trim()
      assertEquals("precious-content", refContent, "Referenced file behind icon symlink must remain untouched")

      // Assert that symlinks are still symlinks
      assertTrue(isSymlink("$icon128Dir/klardrop.png"), "Icon 128 symlink must be preserved")
      assertTrue(isSymlink("$icon256Dir/klardrop.png"), "Icon 256 dangling symlink must be preserved")

      // Case B: Genuine Omarchy installed plugin and payload
      val omarchyHome = "$tempDir/omarchy-home"
      val omarchyBinDir = "$omarchyHome/.local/bin"
      val omarchyDataHome = "$omarchyHome/.local/share"
      execProcess(listOf("mkdir", "-p", omarchyBinDir))
      execProcess(listOf("mkdir", "-p", "$omarchyDataHome/klardrop"))
      execProcess(listOf("sh", "-c", "printf 'omarchy\\n' > '$omarchyDataHome/klardrop/.installer-marker'"))

      val omarchyTargetBin = "$omarchyBinDir/klardrop"
      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$omarchyTargetBin'"))
      chmod(omarchyTargetBin, mode0755)

      // Installed Omarchy plugin
      val omarchyPluginDir = "$omarchyHome/.config/omarchy/plugins/klardrop.omarchy"
      execProcess(listOf("mkdir", "-p", omarchyPluginDir))
      val omarchyManifest = "$omarchyPluginDir/manifest.json"
      execProcess(listOf("sh", "-c", "echo '{\"id\":\"klardrop.omarchy\",\"version\":\"1.0.0\"}' > '$omarchyManifest'"))

      // Protected reference file that an existing icon symlink points to
      val omarchyProtectedFile = "$tempDir/omarchy-protected-ref.png"
      execProcess(listOf("sh", "-c", "echo 'omarchy-precious-content' > '$omarchyProtectedFile'"))

      val omarchyIcon128Dir = "$omarchyDataHome/icons/hicolor/128x128/apps"
      val omarchyIcon256Dir = "$omarchyDataHome/icons/hicolor/256x256/apps"
      execProcess(listOf("mkdir", "-p", omarchyIcon128Dir))
      execProcess(listOf("mkdir", "-p", omarchyIcon256Dir))
      execProcess(listOf("ln", "-s", omarchyProtectedFile, "$omarchyIcon128Dir/klardrop.png"))
      execProcess(listOf("ln", "-s", "$tempDir/omarchy-nonexistent.png", "$omarchyIcon256Dir/klardrop.png"))

      // Staged update tree for Omarchy (stagedBin + stagedTree with omarchy-plugin and icons)
      val omarchyStagedBin = "$omarchyBinDir/.klardrop.new"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$omarchyBinDir/.klardrop-engine.new'"))
      chmod("$omarchyBinDir/.klardrop-engine.new", mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$omarchyStagedBin'"))
      chmod(omarchyStagedBin, mode0755)

      val omarchyStagedDir = "$omarchyBinDir/.klardrop-staged-tree"
      val omarchyStagedPluginDir = "$omarchyStagedDir/share/klardrop/omarchy-plugin"
      execProcess(listOf("mkdir", "-p", omarchyStagedPluginDir))
      execProcess(listOf("sh", "-c", "echo '{\"id\":\"klardrop.omarchy\",\"version\":\"2.0.0\"}' > '$omarchyStagedPluginDir/manifest.json'"))
      execProcess(listOf("mkdir", "-p", "$omarchyStagedDir/share/klardrop/icons/128x128"))
      execProcess(listOf("mkdir", "-p", "$omarchyStagedDir/share/klardrop/icons/256x256"))
      execProcess(listOf("sh", "-c", "echo 'new-omarchy-icon-128' > '$omarchyStagedDir/share/klardrop/icons/128x128/klardrop.png'"))
      execProcess(listOf("sh", "-c", "echo 'new-omarchy-icon-256' > '$omarchyStagedDir/share/klardrop/icons/256x256/klardrop.png'"))

      val omarchyInstaller = LinuxNativeTarballInstaller(
        homeDir = omarchyHome,
        flavor = LinuxFlavor.OMARCHY,
        xdgDataHome = omarchyDataHome,
        isPackageManaged = { false },
        isSystemdManaged = { false },
      )

      val exOmarchy = assertFailsWith<IllegalStateException> {
        omarchyInstaller.applyAndRestart()
      }
      assertEquals("Update installed; restart Klardrop manually", exOmarchy.message)

      // Assert that protected file referenced by symlink was NOT overwritten
      val omarchyRefContent = execProcess(listOf("cat", omarchyProtectedFile)).stdoutString.trim()
      assertEquals("omarchy-precious-content", omarchyRefContent, "Referenced file behind icon symlink must remain untouched during Omarchy update")

      // Assert that symlinks are still symlinks
      assertTrue(isSymlink("$omarchyIcon128Dir/klardrop.png"), "Omarchy icon 128 symlink must be preserved")
      assertTrue(isSymlink("$omarchyIcon256Dir/klardrop.png"), "Omarchy icon 256 dangling symlink must be preserved")

      // Verify binary and plugin were updated
      assertEquals("new-engine", execProcess(listOf("cat", omarchyTargetBin)).stdoutString.trim())
      assertTrue(execProcess(listOf("cat", omarchyManifest)).stdoutString.contains("\"version\":\"2.0.0\""))
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testMissingLauncherPayloadFailsWithoutStageOrTargetChange() = runTest {
    val tempDir = createTempDir("klardrop-test-missing-launcher-payload")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      val dataHome = "$tempHome/.local/share"
      execProcess(listOf("mkdir", "-p", binDir))
      execProcess(listOf("mkdir", "-p", "$dataHome/klardrop"))
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$dataHome/klardrop/.installer-marker'"))

      val targetBin = "$binDir/klardrop"
      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      chmod(targetBin, mode0755)

      // Tarball with klardrop and klardrop-qt, but MISSING bin/klardrop-qt-launcher
      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      val newBin = "$extractRoot/bin/klardrop"
      val newQtBin = "$extractRoot/bin/klardrop-qt"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)

      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$newBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$newQtBin'"))
      chmod(newBin, mode0755)
      chmod(newQtBin, mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        xdgDataHome = dataHome,
        downloader = { _, destFile, _ ->
          execProcess(listOf("cp", fakeTarball, destFile))
        },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}
      }
      assertTrue(ex.message?.contains("missing bin/klardrop-qt-launcher executable") == true)

      // Target engine untouched, and nothing left staged
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim())
      assertFalse(fileExists("$binDir/.klardrop.new"))
      assertFalse(fileExists("$binDir/.klardrop-qt.new"))
      assertFalse(fileExists("$binDir/.klardrop-qt-launcher.new"))
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testQtRuntimeCheckFailurePreventsStagedAndTargetMutation() = runTest {
    val tempDir = createTempDir("klardrop-test-qt-runtime-fail")
    val tempHome = "$tempDir/home"
    try {
      val binDir = "$tempHome/.local/bin"
      val dataHome = "$tempHome/.local/share"
      execProcess(listOf("mkdir", "-p", binDir))
      execProcess(listOf("mkdir", "-p", "$dataHome/klardrop"))
      execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$dataHome/klardrop/.installer-marker'"))

      val targetBin = "$binDir/klardrop"
      val targetQtBin = "$binDir/klardrop-qt"
      val targetQtLauncher = "$binDir/klardrop-qt-launcher"
      execProcess(listOf("sh", "-c", "echo 'old-engine' > '$targetBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-qt' > '$targetQtBin'"))
      execProcess(listOf("sh", "-c", "echo 'old-launcher' > '$targetQtLauncher'"))
      chmod(targetBin, mode0755)
      chmod(targetQtBin, mode0755)
      chmod(targetQtLauncher, mode0755)

      // Staged files: klardrop-qt fails when --check-runtime is passed
      val stagedBin = "$binDir/.klardrop.new"
      val stagedQtBin = "$binDir/.klardrop-qt.new"
      val stagedQtLauncher = "$binDir/.klardrop-qt-launcher.new"
      val stagedEngine = "$binDir/.klardrop-engine.new"
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$stagedEngine'"))
      chmod(stagedEngine, mode0755)
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$stagedBin'"))
      val failingQtScript = "#!/bin/sh\\nif [ \"\$1\" = \"--check-runtime\" ]; then\\n  echo \"Simulated QML runtime check failure\" >&2\\n  exit 1\\nfi\\nexit 0\\n"
      execProcess(listOf("sh", "-c", "printf '$failingQtScript' > '$stagedQtBin'"))
      execProcess(listOf("sh", "-c", "printf '#!/bin/sh\\nexit 0\\n' > '$stagedQtLauncher'"))
      chmod(stagedBin, mode0755)
      chmod(stagedQtBin, mode0755)
      chmod(stagedQtLauncher, mode0755)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        flavor = LinuxFlavor.QT,
        xdgDataHome = dataHome,
        isSystemdManaged = { true },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.applyAndRestart()
      }
      assertTrue(ex.message?.contains("Staged Qt binary failed runtime check") == true, "Got: ${ex.message}")

      // All targets untouched!
      assertEquals("old-engine", execProcess(listOf("cat", targetBin)).stdoutString.trim())
      assertEquals("old-qt", execProcess(listOf("cat", targetQtBin)).stdoutString.trim())
      assertEquals("old-launcher", execProcess(listOf("cat", targetQtLauncher)).stdoutString.trim())
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testTarballInstallPathsCoverEngineAndClient() {
    val home = "/home/testuser"
    assertEquals(
      listOf("$home/.local/bin/klardrop-engine", "$home/.local/bin/klardrop"),
      tarballInstallPaths(home),
    )
    // Either half of the pair running from the install dir means the tarball channel.
    assertEquals(
      InstallChannel.TARBALL,
      detectLinuxInstallChannel(exePath = "$home/.local/bin/klardrop-engine", home = home),
    )
    assertEquals(
      InstallChannel.TARBALL,
      detectLinuxInstallChannel(exePath = "$home/.local/bin/klardrop", home = home),
    )
  }

  @Test
  fun testPackageOwnedClientRefusesBeforeAnythingIsDownloaded() = runTest {
    val tempDir = createTempDir("klardrop-test-pkg-client-pre-download")
    val tempHome = "$tempDir/home"
    try {
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      execProcess(listOf("sh", "-c", "echo 'old-client' > '$tempHome/.local/bin/klardrop'"))
      chmod("$tempHome/.local/bin/klardrop", mode0755)

      var downloadAttempted = false
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        downloader = { _, destFile, _ ->
          downloadAttempted = true
          execProcess(listOf("sh", "-c", "echo tampered > '$destFile'"))
        },
        // Only the Rust CLI is package-owned; the engine is not. Refusing anyway is
        // the point: the pair is only replaceable together.
        isPackageManaged = { it.endsWith("/klardrop") },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", "0".repeat(64))) {}
      }
      assertTrue(ex.message?.contains("Refusing to overwrite package-managed binary (client)") == true, "Got: ${ex.message}")
      assertFalse(downloadAttempted, "A refused update must not download a single byte")
      assertEquals("old-client", execProcess(listOf("cat", "$tempHome/.local/bin/klardrop")).stdoutString.trim())
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testTarballWithoutEngineBinaryIsRefused() = runTest {
    val tempDir = createTempDir("klardrop-test-missing-engine")
    val tempHome = "$tempDir/home"
    try {
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      execProcess(listOf("sh", "-c", "echo 'old-client' > '$tempHome/.local/bin/klardrop'"))
      chmod("$tempHome/.local/bin/klardrop", mode0755)

      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("sh", "-c", "echo 'new-client' > '$extractRoot/bin/klardrop'"))
      chmod("$extractRoot/bin/klardrop", mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        downloader = { _, destFile, _ -> execProcess(listOf("cp", fakeTarball, destFile)) },
        isSystemdManaged = { true },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}
      }
      assertTrue(ex.message?.contains("missing bin/klardrop-engine executable") == true, "Got: ${ex.message}")
      assertEquals("old-client", execProcess(listOf("cat", "$tempHome/.local/bin/klardrop")).stdoutString.trim())
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testFixtureDaemonEntryInTarballIsRefused() {
    assertFailsWith<SecurityException> {
      validateTarEntries(
        listOf("-rwxr-xr-x u/g 100 2026-09-26 12:00 klardrop-native-linux-x64/bin/klardrop-fixture-daemon"),
      )
    }
    assertFailsWith<SecurityException> {
      validateTarEntries(
        listOf("-rwxr-xr-x u/g 100 2026-09-26 12:00 klardrop-native-linux-x64/bin/klardrop-fixture-daemon-9f2a1c"),
      )
    }
  }

  @Test
  fun testWrongArchitectureClientIsRefusedBeforeReplacingAnything() = runTest {
    val tempDir = createTempDir("klardrop-test-wrong-arch")
    val tempHome = "$tempDir/home"
    try {
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      execProcess(listOf("sh", "-c", "echo 'old-client' > '$tempHome/.local/bin/klardrop'"))
      chmod("$tempHome/.local/bin/klardrop", mode0755)

      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("sh", "-c", "echo 'new-engine' > '$extractRoot/bin/klardrop-engine'"))
      chmod("$extractRoot/bin/klardrop-engine", mode0755)
      // An ELF header for the *other* architecture: EM_AARCH64 (0xB7) on an x64 test
      // runner. Only the first 20 bytes are read (e_ident + e_type + e_machine).
      val wrongArchClient = "$extractRoot/bin/klardrop"
      val py = """
import sys
machine = 0xB7
hdr = bytearray(20)
hdr[0:4] = b'\x7fELF'
hdr[4] = 2
hdr[5] = 1
hdr[18] = machine & 0xFF
hdr[19] = (machine >> 8) & 0xFF
open(sys.argv[1], 'wb').write(bytes(hdr))
""".trimIndent()
      execProcess(listOf("python3", "-c", py, wrongArchClient))
      chmod(wrongArchClient, mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        downloader = { _, destFile, _ -> execProcess(listOf("cp", fakeTarball, destFile)) },
        isSystemdManaged = { true },
      )

      val ex = assertFailsWith<IllegalStateException> {
        installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}
      }
      assertTrue(ex.message?.contains("Staged client binary is built for ELF machine") == true, "Got: ${ex.message}")
      assertFalse(fileExists("$tempHome/.local/bin/klardrop.new"), "Nothing may be staged")
      assertEquals("old-client", execProcess(listOf("cat", "$tempHome/.local/bin/klardrop")).stdoutString.trim())
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  @Test
  fun testHostElfBinariesStageAndApplyAsAPair() = runTest {
    val tempDir = createTempDir("klardrop-test-host-elf")
    val tempHome = "$tempDir/home"
    try {
      execProcess(listOf("mkdir", "-p", "$tempHome/.local/bin"))
      // This test binary is a real ELF for the host arch, so the architecture check
      // must accept it — the guard rejects the wrong machine, not every machine.
      val hostElf = requireNotNull(currentExecutablePath()) { "Test process must have a resolvable executable path" }
      execProcess(listOf("cp", hostElf, "$tempHome/.local/bin/klardrop"))
      chmod("$tempHome/.local/bin/klardrop", mode0755)

      val stage = "$tempDir/stage"
      val extractRoot = "$stage/klardrop-native-linux-x64"
      execProcess(listOf("mkdir", "-p", "$extractRoot/bin"))
      execProcess(listOf("cp", hostElf, "$extractRoot/bin/klardrop"))
      execProcess(listOf("cp", hostElf, "$extractRoot/bin/klardrop-engine"))
      chmod("$extractRoot/bin/klardrop", mode0755)
      chmod("$extractRoot/bin/klardrop-engine", mode0755)

      val fakeTarball = "$tempDir/klardrop-native-linux-x64.tar.gz"
      execProcess(listOf("tar", "-czf", fakeTarball, "-C", stage, "klardrop-native-linux-x64"))
      val sha = computeFileSha256(fakeTarball)

      val restartLog = "$tempDir/restart.log"
      val installer = LinuxNativeTarballInstaller(
        homeDir = tempHome,
        restartCommand = listOf("sh", "-c", "echo restarted > '$restartLog'"),
        downloader = { _, destFile, _ -> execProcess(listOf("cp", fakeTarball, destFile)) },
        isSystemdManaged = { true },
      )

      installer.downloadAndStage(ReleaseAsset("https://example.com/asset.tar.gz", sha)) {}
      installer.applyAndRestart()

      assertTrue(isExecutable("$tempHome/.local/bin/klardrop"))
      assertTrue(isExecutable("$tempHome/.local/bin/klardrop-engine"))
      assertEquals(
        computeFileSha256(hostElf),
        computeFileSha256("$tempHome/.local/bin/klardrop"),
        "Client must be the staged binary from this release",
      )
      assertEquals(
        computeFileSha256(hostElf),
        computeFileSha256("$tempHome/.local/bin/klardrop-engine"),
        "Engine must be the staged binary from this release",
      )
      assertTrue(fileExists(restartLog))
    } finally {
      execProcess(listOf("rm", "-rf", tempDir))
    }
  }

  private fun createTempDir(prefix: String): String {
    return makeTempDir("/tmp", prefix)
  }

  private fun writeValidQtMarker(home: String, dataHome: String = "$home/.local/share") {
    execProcess(listOf("mkdir", "-p", "$dataHome/klardrop"))
    execProcess(listOf("sh", "-c", "printf 'qt\\n' > '$dataHome/klardrop/.installer-marker'"))
  }
}

