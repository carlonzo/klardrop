package com.carlom.klardrop.common.communication

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket

/**
 * T10 firewall punch-through dial: connects to [remoteAddress] from a socket BOUND to
 * (the local address that routes to the peer, [localBindPort]) — in practice our own
 * listening port, co-bound via reuseAddress + reusePort (the listener sets the same pair,
 * see [Server]; Linux rejects a REUSEADDR-only co-bind against a LISTENing socket). The
 * outbound SYN then creates conntrack state whose reverse direction is the peer's inbound
 * SYN to our listening port, which stateful firewalls (ufw / nft / conntrack-based APs)
 * accept as ESTABLISHED — a TCP simultaneous open when the peer dials us the same way in
 * an overlapping window.
 *
 * Returns null when the platform cannot perform the bound dial or the connect failed
 * (bind conflict, refusal, timeout). Callers treat null as one failed attempt — never as
 * a signal to invalidate the dialed endpoint.
 */
internal expect suspend fun punchThroughConnect(
  selectorManager: SelectorManager,
  remoteAddress: InetSocketAddress,
  localBindPort: Int,
): Socket?

/**
 * Whether this platform can perform the bound dial at all. False on targets whose
 * [punchThroughConnect] is a stub, so callers skip the burst instead of paying its full
 * attempt/backoff schedule for dials that are guaranteed to return null.
 */
internal expect val punchThroughSupported: Boolean
