package com.carlom.klardrop.common

data class ApplicationInfo(
  val isDebug: Boolean = false,

  // useful for testing on desktop to run multiple instance in the same machine
  val disablePersistence: Boolean = false,

  /** Desktop only: back the clipboard with an in-memory string instead of the real AWT/system
   *  clipboard. Tests set this so they never read/write the developer's actual desktop
   *  clipboard. */
  val disableSystemClipboard: Boolean = false,

  val enableKlardropServer: Boolean = true,

  val enableNearbyServer: Boolean = true,

  /** BLE advertise / scan / GATT. Independent of the TCP servers so tests can isolate transports. */
  val enableBle: Boolean = true,

  /**
   * Loopback HTTP control port for UI-equivalent actions (pair, send, accept) used by
   * `klardrop`, the native CLI/TUI and the Qt / Omarchy frontends. Null means do not start the
   * server. Honored in every build; `0` binds an ephemeral port (still published to
   * control.json).
   */
  val controlPort: Int? = null,

  val appVersion: String = KlardropVersion.VERSION
)
