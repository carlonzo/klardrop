package com.carlom.klardrop.common.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T11 failing-first spec for the restriction derivation: the mapping from raw
 * PowerManager/ConnectivityManager state to the restricted/reasons model must hold
 * before the monitor exists (red = compile failure of this file), and keep holding
 * after (green).
 */
class ConnectivityRestrictionsDerivationTest {

  @Test
  fun `battery saver on and not exempt blocks`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = true,
      batteryOptimizationExempt = false,
      activeNetworkMetered = false,
      userDeniedOnMetered = false,
    )
    assertTrue(r.batterySaverBlocking)
    assertTrue(r.batteryOptimizationNotExempt)
    assertFalse(r.meteredNetworkDenied)
    assertTrue(r.restricted)
    assertEquals(
      setOf(ConnectivityRestriction.BatterySaverBlocking, ConnectivityRestriction.BatteryOptimizationNotExempt),
      r.reasons,
    )
  }

  @Test
  fun `battery saver on but exempt does not block`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = true,
      batteryOptimizationExempt = true,
      activeNetworkMetered = false,
      userDeniedOnMetered = false,
    )
    assertFalse(r.batterySaverBlocking)
    assertFalse(r.batteryOptimizationNotExempt)
    assertFalse(r.restricted)
    assertEquals(emptySet<ConnectivityRestriction>(), r.reasons)
  }

  @Test
  fun `battery saver off but not exempt is a risk flag only`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = false,
      batteryOptimizationExempt = false,
      activeNetworkMetered = false,
      userDeniedOnMetered = false,
    )
    assertFalse(r.batterySaverBlocking)
    assertTrue(r.batteryOptimizationNotExempt)
    // A risk flag is not a blocker: it is reported in `reasons` but must not raise the
    // banner, or every app that was never whitelisted shows a permanent warning.
    assertFalse(r.restricted)
    assertNull(r.activeBlocker)
    assertEquals(setOf(ConnectivityRestriction.BatteryOptimizationNotExempt), r.reasons)
  }

  @Test
  fun `metered network with user deny blocks`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = false,
      batteryOptimizationExempt = true,
      activeNetworkMetered = true,
      userDeniedOnMetered = true,
    )
    assertTrue(r.meteredNetworkDenied)
    assertTrue(r.restricted)
    assertEquals(setOf(ConnectivityRestriction.MeteredNetworkDenied), r.reasons)
  }

  @Test
  fun `metered network without user deny does not block`() {
    // The T1 finding conjunct: metered alone is not enough — the user must have
    // denied the app on metered nets (metered_deny_user chain) for the banner to fire.
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = false,
      batteryOptimizationExempt = true,
      activeNetworkMetered = true,
      userDeniedOnMetered = false,
    )
    assertFalse(r.meteredNetworkDenied)
    assertFalse(r.restricted)
  }

  @Test
  fun `user deny on unmetered network does not block`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = false,
      batteryOptimizationExempt = true,
      activeNetworkMetered = false,
      userDeniedOnMetered = true,
    )
    assertFalse(r.meteredNetworkDenied)
    assertFalse(r.restricted)
  }

  @Test
  fun `all restrictions combine into the reasons set`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = true,
      batteryOptimizationExempt = false,
      activeNetworkMetered = true,
      userDeniedOnMetered = true,
    )
    assertEquals(
      setOf(
        ConnectivityRestriction.BatterySaverBlocking,
        ConnectivityRestriction.BatteryOptimizationNotExempt,
        ConnectivityRestriction.MeteredNetworkDenied,
      ),
      r.reasons,
    )
  }

  @Test
  fun `active blocker notice names battery saver first`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = true,
      batteryOptimizationExempt = false,
      activeNetworkMetered = true,
      userDeniedOnMetered = true,
    )
    assertEquals("Battery saver is blocking Klardrop", r.activeBlockerNotice())
  }

  @Test
  fun `active blocker notice names metered deny when saver is not blocking`() {
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = false,
      batteryOptimizationExempt = true,
      activeNetworkMetered = true,
      userDeniedOnMetered = true,
    )
    assertEquals("Klardrop is blocked on metered networks", r.activeBlockerNotice())
  }

  @Test
  fun `no active blocker means no failure prefix`() {
    // battery-optimization-not-exempt alone never blocked a connection, so it must
    // not prefix a pairing failure — only active blockers explain connect failures.
    val r = ConnectivityRestrictions.derive(
      powerSaveMode = false,
      batteryOptimizationExempt = false,
      activeNetworkMetered = false,
      userDeniedOnMetered = false,
    )
    assertFalse(r.restricted)
    assertNull(r.activeBlockerNotice())
    assertNull(ConnectivityRestrictions.EMPTY.activeBlockerNotice())
  }
}
