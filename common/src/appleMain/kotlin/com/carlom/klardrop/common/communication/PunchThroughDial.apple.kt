package com.carlom.klardrop.common.communication

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket

// ponytail: punch-through is desktop-only for now (the plan's firewall evidence is desktop
// ufw/nft). Add a posix actual (bind + connect via ktor's posix selector) when macOS pf /
// iOS need it.
internal actual suspend fun punchThroughConnect(
  selectorManager: SelectorManager,
  remoteAddress: InetSocketAddress,
  localBindPort: Int,
): Socket? = null
