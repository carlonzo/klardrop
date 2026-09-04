package com.carlom.klardrop.common

data class ApplicationInfo(
  val isDebug: Boolean = false,

  // useful for testing on desktop to run multiple instance in the same machine
  val disablePersistence: Boolean = false,

  val enableKlardropServer: Boolean = true,

  val enableNearbyServer: Boolean = true,

  /** BLE advertise / scan / GATT. Independent of the TCP servers so tests can isolate transports. */
  val enableBle: Boolean = true,

  /**
   * Loopback HTTP control port for autonomous UI-equivalent actions (pair, send, accept).
   * Null means do not start the server. Only honored when [isDebug] is true.
   */
  val controlPort: Int? = null,

  val appVersion: String = KlardropVersion.VERSION
)
