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
    channel: InstallChannel = InstallChannel.AUR,
    osType: OsType = OsType.LINUX,
    fetcher: UpdateManifestFetcher? = UpdateManifestFetcher { fetched },
  ) = UpdateChecker(
    currentVersion = current,
    osType = osType,
    fetcher = fetcher,
    detectChannel = { channel },
    coroutines = TestCoroutines(dispatcher),
  )

  @Test
  fun aurChannelYieldsCopyableCommand() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, channel = InstallChannel.AUR)
    checker.checkNow()
    advanceUntilIdle()

    val status = checker.status.value
    assertIs<UpdateStatus.Available>(status)
    assertEquals("0.2.0", status.version)
    assertEquals(InstallChannel.AUR, status.channel)
    assertEquals(UpdateAction.RunCommand("yay -S klardrop-bin"), status.action)
  }

  @Test
  fun manualChannelOpensPlatformAsset() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val checker = checker(dispatcher, channel = InstallChannel.TARBALL, osType = OsType.LINUX)
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
