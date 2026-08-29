package com.carlom.klardrop.common.communication

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket

// ponytail: punch-through is desktop-only for now (the plan's firewall evidence is desktop
// ufw/nft; the phone-side Battery-Saver drop is T11's problem and needs its own fix).
// Add a JVM actual mirroring PunchThroughDial.desktopJvm.kt when Android needs it.
internal actual suspend fun punchThroughConnect(
  selectorManager: SelectorManager,
  remoteAddress: InetSocketAddress,
  localBindPort: Int,
): Socket? = null
