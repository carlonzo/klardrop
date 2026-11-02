package com.carlom.klardrop.common

public data class ApplicationInfo(
  public val isDebug: Boolean = false,

  // useful for testing on desktop to run multiple instance in the same machine
  public val disablePersistence: Boolean = false,

  /** Desktop only: back the clipboard with an in-memory string instead of the real AWT/system
   *  clipboard. Tests set this so they never read/write the developer's actual desktop
   *  clipboard. */
  public val disableSystemClipboard: Boolean = false,

  public val enableKlardropServer: Boolean = true,

  public val enableNearbyServer: Boolean = true,

  /** BLE advertise / scan / GATT. Independent of the TCP servers so tests can isolate transports. */
  public val enableBle: Boolean = true,

  /**
   * Loopback HTTP control port for UI-equivalent actions (pair, send, accept) used by
   * `klardrop`, the native CLI/TUI and the Qt / Omarchy frontends. Null means do not start the
   * server. Honored in every build; `0` binds an ephemeral port (still published to
   * control.json).
   */
  public val controlPort: Int? = null,

  /**
   * TCP port the Klardrop server tries first. Being the same on every install lets a peer that
   * knows only our tailnet address find us. When another process holds it, the server moves to a
   * remembered fallback port (or a fresh one) and peers learn it from the handshake. 0 = always
   * an ephemeral port.
   */
  public val listenPort: Int = DEFAULT_LISTEN_PORT,

  public val appVersion: String = KlardropVersion.VERSION
)

/** See [ApplicationInfo.listenPort]. Unassigned by IANA. */
public const val DEFAULT_LISTEN_PORT: Int = 47470
