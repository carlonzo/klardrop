package com.carlom.klardrop.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.carlom.klardrop.common.connectivity.ConnectivityRestrictions
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * T11 instrumented verification on a real Android (emulator) OS:
 *
 *  1. Battery Saver engaged via the low-battery simulation (trigger level + unplug)
 *     reaches the ConnectivityRestrictionMonitor (batterySaverBlocking=true) and the
 *     discovery-screen banner appears.
 *  2. Tapping the banner launches the OS-standard battery-optimization exemption
 *     dialog; after `dumpsys deviceidle whitelist +<pkg>` (what Allow does) and the
 *     dialog returning (which triggers refresh()), the restriction clears and the
 *     banner disappears.
 *  3. Monitor state survives Activity recreation (singleton + re-registration).
 *  4. A real pair attempt against a closed port on the emulator's host produces the
 *     T5 failure reason, prefixed with the active restriction.
 *
 * The metered-denied derivation is covered JVM-side (pure derive()); the emulator
 * has no way to reproduce the user's per-app metered deny.
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityRestrictionTest {

  // Keep in sync with ConnectivityRestrictionBanner in compose-ui discovery_screen.kt.
  private val batteryBannerText = "Battery saver is blocking Klardrop connections — Tap to allow"

  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val packageName = instrumentation.targetContext.packageName

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @After
  fun restoreOsState() {
    setBatterySaver(on = false)
    shell("dumpsys", "deviceidle", "whitelist", "-$packageName")
  }

  /**
   * Battery Saver toggle that actually engages PowerManagerService on the emulator.
   * `settings put global low_power 1` alone does NOT flip isPowerSaveMode on this
   * image (the setting is never wired into the state machine) — the documented
   * fallback is the low-battery simulation: arm the automatic trigger level, then
   * unplug + drain the battery, and the OS engages battery saver itself (and
   * broadcasts ACTION_POWER_SAVE_MODE_CHANGED, which is what the monitor listens to).
   */
  private fun setBatterySaver(on: Boolean) {
    if (on) {
      shell("settings", "put", "global", "low_power_trigger_level", "15")
      shell("dumpsys", "battery", "unplug")
      shell("dumpsys", "battery", "set", "level", "10")
    } else {
      shell("dumpsys", "battery", "reset")
      shell("settings", "put", "global", "low_power_trigger_level", "0")
    }
  }

  @Test
  fun batterySaverToggleReachesMonitorBannerAndClearsAfterWhitelist() {
    // Baseline: saver off, app NOT exempt → the not-exempt risk flag is reported but is NOT
    // an active blocker, so no banner yet. Only battery saver actually engaging raises one.
    setBatterySaver(on = false)
    shell("dumpsys", "deviceidle", "whitelist", "-$packageName")
    awaitRestrictions { it.batteryOptimizationNotExempt && !it.batterySaverBlocking && !it.restricted }
    waitUntilGone(batteryBannerText)

    // Battery Saver ON → powersave chain default-denies our uid → batterySaverBlocking.
    setBatterySaver(on = true)
    awaitRestrictions { it.batterySaverBlocking }
    waitUntilShown(batteryBannerText)

    // Tap → OS-standard exemption dialog; whitelist (what Allow writes); dismiss the
    // dialog → the app refreshes → restriction cleared, banner gone.
    composeRule.onNodeWithText(batteryBannerText).performClick()
    Thread.sleep(2_000) // let the system dialog come up
    shell("dumpsys", "deviceidle", "whitelist", "+$packageName")
    shell("input", "keyevent", "KEYCODE_BACK")
    awaitRestrictions { !it.restricted }
    waitUntilGone(batteryBannerText)
  }

  @Test
  fun monitorStateSurvivesActivityRecreation() {
    setBatterySaver(on = false)
    shell("dumpsys", "deviceidle", "whitelist", "-$packageName")
    setBatterySaver(on = true)
    awaitRestrictions { it.batterySaverBlocking }

    // Tear the activity down and rebuild it: the monitor is an app-scoped singleton,
    // so the fresh composition must re-register its collectors against the SAME
    // monitor and still see OS state (stale_state: no per-activity monitor, no
    // dead receivers). Asserted via a fresh observe() snapshot — deliberately no
    // post-recreate compose sync, which is unreliable on headless emulators.
    composeRule.activityRule.scenario.recreate()
    awaitRestrictions { it.batterySaverBlocking }
  }

  @Test
  fun pairingFailureAgainstClosedHostPortIsPrefixedWithRestriction() {
    setBatterySaver(on = true)
    awaitRestrictions { it.batterySaverBlocking }

    // Inject a ghost peer whose Klardrop endpoint is a closed port on the emulator's
    // host (10.0.2.2 = host loopback from the emulator). Real pair attempt → real
    // T5 failure reason → prefixed with the active restriction. The BLE connection
    // keeps the row alive after the refused dial invalidates the TCP endpoint —
    // otherwise the row drops out of the list before the error can render.
    runBlocking {
      instrumentation.targetContext.appKlardrop().commonComponent.visibleDevices().onNewDeviceVisible(
        DeviceInfo(deviceId = "t11-ghost", name = "Ghost Peer", deviceType = DeviceType.DESKTOP),
        DeviceConnection.KlardropConnection(address = "10.0.2.2", port = 1),
      )
      instrumentation.targetContext.appKlardrop().commonComponent.visibleDevices().onNewDeviceVisible(
        DeviceInfo(deviceId = "t11-ghost", name = "Ghost Peer", deviceType = DeviceType.DESKTOP),
        DeviceConnection.BleConnection(address = "t11-ghost-ble"),
      )
    }
    waitUntilShown("Ghost Peer")

    // The Nearby section can sit below the fold (permissions panel above it) —
    // scroll the row into view before tapping, or the touch injection fails.
    composeRule.onNodeWithText("Pair").performScrollTo().performClick()
    composeRule.onNodeWithText("Yes, it's mine").performClick()

    // The prefixed failure renders as red helper text under the device row.
    composeRule.waitUntil(90_000) {
      composeRule.onAllNodesWithText(
        "Battery saver is blocking Klardrop — Could not reach Ghost Peer",
        substring = true,
      ).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun monitor() = instrumentation.targetContext.appKlardrop().commonComponent.connectivityRestrictionMonitor()

  /**
   * Poll the monitor until [predicate] holds. Each attempt collects the cold
   * observe() flow, whose onStart emits a fresh OS snapshot — so this works
   * whether or not the OS broadcast fired while we weren't looking.
   */
  private fun awaitRestrictions(timeoutMs: Long = 15_000, predicate: (ConnectivityRestrictions) -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val match = runBlocking {
        withTimeoutOrNull(2_000) { monitor().observe().first { predicate(it) } }
      }
      if (match != null) return
    }
    error("restriction state not reached within ${timeoutMs}ms")
  }

  private fun waitUntilShown(text: String) {
    composeRule.waitUntil(15_000) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithText(text).assertIsDisplayed()
  }

  private fun waitUntilGone(text: String) {
    composeRule.waitUntil(15_000) {
      composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }
  }

  private fun shell(vararg cmd: String): String =
    instrumentation.uiAutomation.executeShellCommand(cmd.joinToString(" ")).use { pfd ->
      BufferedReader(InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)))
        .readText()
        .trim()
    }
}
