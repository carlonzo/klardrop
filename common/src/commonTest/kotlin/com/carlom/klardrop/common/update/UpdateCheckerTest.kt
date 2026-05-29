package com.carlom.klardrop.common.update

import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.OsType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
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
    platforms = mapOf(
      "linux-tarball" to ReleaseAsset("https://example/klardrop-linux-x64.tar.gz"),
      "macos" to ReleaseAsset("https://example/klardrop.dmg"),
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
  ) = UpdateChecker(
    currentVersion = current,
    osType = osType,
    fetcher = fetcher,
    detectChannel = { channel },
    coroutines = TestCoroutines(dispatcher),
    installerFactory = installerFactory,
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
  fun failedFetchStaysUnknown() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, fetcher = UpdateManifestFetcher { null })
    checker.checkNow()
    advanceUntilIdle()

    assertTrue(checker.status.value is UpdateStatus.Unknown)
  }
}
