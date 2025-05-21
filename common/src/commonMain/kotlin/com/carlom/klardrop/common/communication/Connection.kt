package com.carlom.klardrop.common.communication

import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.remoteAddress
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel

class Connection(
    val socket: Socket,
    val deviceId: String
) {
    val input: ByteReadChannel get() = socket.openReadChannel()
    val output: ByteWriteChannel get() = socket.openWriteChannel(autoFlush = true)

    // Expose socket's remote address as it can be useful for logging or identification
    val remoteAddress get() = socket.remoteAddress

    fun isClosed(): Boolean {
        return socket.isClosed
    }

    // Consider adding a close method to encapsulate socket closing logic if needed later.
    // fun close() {
    //     socket.close()
    // }
}
