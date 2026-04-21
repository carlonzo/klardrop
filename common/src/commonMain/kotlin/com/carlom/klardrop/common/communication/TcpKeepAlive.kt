package com.carlom.klardrop.common.communication

import io.ktor.network.sockets.Socket

/**
 * Best-effort: enable kernel-level TCP keep-alive on the underlying socket.
 *
 * Used for accepted server sockets where Ktor does not expose a configuration
 * block (only `connect(...)` does). Implementations are platform-specific
 * because Ktor's `SocketImpl` is `internal`. On platforms where we can't poke
 * the underlying socket (Apple targets), this is a no-op and the OS default
 * applies (typically off).
 */
internal expect fun Socket.enableTcpKeepAlive()
