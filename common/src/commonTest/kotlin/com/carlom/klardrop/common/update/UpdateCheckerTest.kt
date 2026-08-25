package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class TestCoroutines(private val dispatcher: CoroutineDispatcher) : Coroutines {
  override fun newScope() = CoroutineScope(dispatcher)
  override fun newScope(context: CoroutineContext) = CoroutineScope(context)
  override val appScope = CoroutineScope(dispatcher)
  override val ioDispatcher = dispatcher
  override val mainDispatcher = dispatcher
  override val cpuDispatcher = dispatcher
}

class UpdateCheckerTest {

  private fun manifest(version: String) = LatestManifest(
    version = version,
    notes = "https://example/notes/$version",
    platforms = mapOf(
      "linux-tarball" to ReleaseAsset("https://example/klardrop-linux-x64.tar.gz"),
      "linux-deb" to ReleaseAsset("https://example/klardrop_1.0.0_amd64.deb"),
      "linux-rpm" to ReleaseAsset("https://example/klardrop-1.0.0.x86_64.rpm"),
      "macos" to ReleaseAsset("https://example/klardrop.dmg"),
      "windows" to ReleaseAsset("https://example/klardrop.msi"),
    ),
  )

  private fun checker(
    dispatcher: CoroutineDispatcher,
    current: String = "0.1.0",
    fetched: LatestManifest? = manifest("0.2.0"),
    channel: InstallChannel = InstallChannel.TARBALL,
    osType: OsType = OsType.LINUX,
    fetcher: UpdateManifestFetcher? = UpdateManifestFetcher { fetched },
    // Default to "no self-installer" so the fallback action is exercised and tests
    // stay hermetic (no real download on the test JVM).
    installerFactory: (InstallChannel) -> UpdateInstaller? = { null },
    recheckInterval: Duration = 6.hours,
  ) = UpdateChecker(
    currentVersion = current,
    osType = osType,
    fetcher = fetcher,
    detectChannel = { channel },
    coroutines = TestCoroutines(dispatcher),
    installerFactory = installerFactory,
    recheckInterval = recheckInterval,
  )

  @Test
  fun brewChannelYieldsCopyableCommand() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, channel = InstallChannel.BREW)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals("0.2.0", status.version)
    assertEquals(InstallChannel.BREW, status.channel)
    assertEquals(UpdateAction.RunCommand("brew upgrade --cask klardrop"), status.action)
  }

  @Test
  fun tarballChannelWithoutSelfInstallerFallsBackToReinstall() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, channel = InstallChannel.TARBALL, osType = OsType.LINUX)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals(
      UpdateAction.RunCommand("curl -fsSL ${UpdateChecker.INSTALL_SCRIPT_URL} | bash"),
      status.action,
    )
    // No installer -> no self-update in flight.
    assertEquals(InstallProgress.Idle, checker.install.value)
  }

  @Test
  fun manualChannelOpensPlatformAsset() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, channel = InstallChannel.MANUAL, osType = OsType.LINUX)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals(
      UpdateAction.OpenUrl("https://example/klardrop-linux-x64.tar.gz"),
      status.action,
    )
  }

  @Test
  fun selfInstallerDownloadsAndBecomesReady() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val fake = object : UpdateInstaller {
      var staged = false
      override suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit) {
        onProgress(0.5f)
        onProgress(1f)
        staged = true
      }
      override fun applyAndRestart() = error("not called in test")
    }
    val checker = checker(
      dispatcher,
      channel = InstallChannel.TARBALL,
      installerFactory = { fake },
    )
    checker.checkNow()
    advanceUntilIdle()

    assertIs<UpdateStatus.Available>(checker.status.value)
    assertTrue(fake.staged)
    assertEquals(InstallProgress.Ready, checker.install.value)
  }

  @Test
  fun sameVersionIsUpToDate() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, current = "0.2.0", fetched = manifest("0.2.0"))
    checker.checkNow()
    advanceUntilIdle()

    assertEquals(UpdateStatus.UpToDate, checker.status.value)
  }

  @Test
  fun nullFetcherStaysUnknown() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, fetcher = null)
    checker.checkNow()
    advanceUntilIdle()

    assertEquals(UpdateStatus.Unknown, checker.status.value)
  }

  @Test
  fun failedFetchIsReported() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, fetcher = UpdateManifestFetcher { null })
    checker.checkNow()
    advanceUntilIdle()

    assertIs<UpdateStatus.Failed>(checker.status.value)
  }

  // --- install channels ------------------------------------------------------
  // Each system package manager owns its own files, so the only correct advice is
  // that manager's own upgrade path. Getting this wrong is worse than saying nothing:
  // it tells the user to overwrite files a package database still claims to own.

  @Test
  fun debChannelDownloadsThePackageAndHandsItToApt() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, channel = InstallChannel.DEB)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals(
      UpdateAction.RunCommand(
        "curl -fsSLO https://example/klardrop_1.0.0_amd64.deb && sudo apt install ./klardrop_1.0.0_amd64.deb"
      ),
      status.action,
    )
  }

  @Test
  fun rpmChannelDownloadsThePackageAndUpgradesInPlace() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, channel = InstallChannel.RPM)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals(
      UpdateAction.RunCommand(
        "curl -fsSLO https://example/klardrop-1.0.0.x86_64.rpm && sudo rpm -Uvh ./klardrop-1.0.0.x86_64.rpm"
      ),
      status.action,
    )
  }

  @Test
  fun packageManagerChannelsUseTheirOwnUpgradeCommand() = runTest {
    val expected = mapOf(
      InstallChannel.PACMAN to "yay -S klardrop-bin",
      InstallChannel.FLATPAK to "flatpak update com.carlom.Klardrop",
      InstallChannel.SNAP to "sudo snap refresh klardrop",
      InstallChannel.NIX to "nix profile upgrade klardrop",
    )
    for ((channel, command) in expected) {
      val dispatcher = StandardTestDispatcher(testScheduler)
      val checker = checker(dispatcher, channel = channel)
      checker.checkNow()
      advanceUntilIdle()

      val status = checker.status.value
      assertIs<UpdateStatus.Available>(status, "channel $channel")
      assertEquals(UpdateAction.RunCommand(command), status.action, "channel $channel")
    }
  }

  @Test
  fun channelWithoutItsAssetFallsBackToADownloadLink() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    // A release that predates the .rpm channel: no linux-rpm asset to point at.
    val withoutRpm = manifest("0.2.0").let { it.copy(platforms = it.platforms - "linux-rpm") }
    val checker = checker(dispatcher, channel = InstallChannel.RPM, fetched = withoutRpm)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals(
      UpdateAction.OpenUrl("https://example/klardrop-linux-x64.tar.gz"),
      status.action,
    )
  }

  @Test
  fun availableCarriesReleaseNotes() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals("https://example/notes/0.2.0", status.notesUrl)
  }

  // --- checking lifecycle ----------------------------------------------------

  @Test
  fun periodicLoopRechecksUntilStopped() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    var fetches = 0
    val checker = checker(
      dispatcher,
      fetcher = UpdateManifestFetcher { fetches++; manifest("0.2.0") },
      recheckInterval = 1.hours,
    )

    checker.start()
    runCurrent()
    assertEquals(1, fetches, "checks once immediately")

    advanceTimeBy(1.hours + 1.minutes)
    runCurrent()
    assertEquals(2, fetches, "and again one interval later")

    // Leaves nothing scheduled behind — an un-stopped loop would hang runTest.
    checker.stop()
  }

  @Test
  fun aLaterFailedCheckDoesNotHideAKnownUpdate() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    var fail = false
    val checker = checker(
      dispatcher,
      fetcher = UpdateManifestFetcher { if (fail) null else manifest("0.2.0") },
    )

    checker.checkNow()
    advanceUntilIdle()
    assertIs<UpdateStatus.Available>(checker.status.value)

    // GitHub blips, or the machine went offline: the banner must not vanish.
    fail = true
    checker.checkNow()
    advanceUntilIdle()
    assertIs<UpdateStatus.Available>(checker.status.value)
  }

  @Test
  fun stagedUpdateIsNotRedownloadedOnRecheck() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    var downloads = 0
    val fake = object : UpdateInstaller {
      override suspend fun downloadAndStage(asset: ReleaseAsset, onProgress: (Float?) -> Unit) {
        downloads++
      }
      override fun applyAndRestart() = error("not called in test")
    }
    val checker = checker(
      dispatcher,
      channel = InstallChannel.TARBALL,
      installerFactory = { fake },
    )

    checker.checkNow()
    advanceUntilIdle()
    assertEquals(InstallProgress.Ready, checker.install.value)

    checker.checkNow()
    advanceUntilIdle()
    assertEquals(1, downloads, "a staged update is not downloaded again")
    assertEquals(InstallProgress.Ready, checker.install.value)
  }
}
