package com.carlom.klardrop.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.util.network.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.selects.select

@Composable
fun App() {
    val socketStuff = remember { Socketstuff() }
    val socketState by socketStuff.channelFlow.flowOn(Dispatchers.IO).collectAsState("Nothing yet.")

    var sendMessageCounter by remember { mutableStateOf(0) }

    Column(Modifier.padding(16.dp)) {

        Text(text = "Received: $socketState")

        Button(onClick = {
            GlobalScope.launch(Dispatchers.IO) {
                sendMessageCounter++
                socketStuff.sendPackage(sendMessageCounter)
            }
        }) {
            Text("Send")
        }

    }

}

const val BROADCAST_PORT = 2121


class Socketstuff {

    private val sendSockets by lazy {
        val selectorManager = SelectorManager(Dispatchers.IO)

        val socketAddress: SocketAddress = InetSocketAddress("255.255.255.255", BROADCAST_PORT)

        aSocket(selectorManager).udp().connect(socketAddress) {
            broadcast = true
        }.also {
            log("Client connected with: ${socketAddress}")

            GlobalScope.launch(Dispatchers.IO) {

                it.incoming.consumeEach {
                    log("Received udp datagram from broadcast channel: ${it.address}: $it ")
                }

            }
        }

    }

    val channelFlow = callbackFlow<String> {

        val soc = openSocket()

        log("Listening on ${soc.localAddress}")

        val readChannel = soc.openReadChannel()

        while (true) {

//            kotlin.runCatching { readChannel.readUTF8Line(10) }
//                .onSuccess { log("Read $it") }
//                .onFailure { log("Failed reading: ${it}") }
//
            delay(1000)



            log("waiting receive ${soc}")
            readChannel.awaitContent()
            log("received something? ")

            val input =   soc.incoming.receive()

            log("received? ${input.packet}")

            val read = input.packet.readText(0, 10)
            log(read)
        }

//        soc.incoming.receiveAsFlow().collect {
//
//            log("Received udp datagram from ${it.address}: $it ")
//
//            send(it.packet.readText())
//        }

//        awaitClose {
//            soc.close()
//        }
    }


    private fun openSocket(): BoundDatagramSocket {

        val selectorManager = SelectorManager(Dispatchers.IO)

        val socketAddress: SocketAddress = InetSocketAddress("localhost", BROADCAST_PORT)
        val serverSocket = aSocket(selectorManager).udp().bind(socketAddress) {
//            broadcast = true
        }

        return serverSocket
    }

    suspend fun sendPackage(counter: Int) {

        val address = InetSocketAddress(sendSockets.remoteAddress.toJavaAddress().hostname, BROADCAST_PORT)

        log("Sending packet to: ${address}")
        val datagram = Datagram(
            ByteReadPacket("Message number: $counter!!\n".encodeToByteArray()),
            address
        )

        sendSockets.send(datagram)
    }

}

fun log(message: String) {
    println("[Klardrop] $message")
}