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
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

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

    init {
        GlobalScope.launch(Dispatchers.IO) {
            var counter = 0
            while (true) {
                sendPackage(counter)
                counter++
                delay(2000)
            }
        }
    }


    private val serverSocket: BoundDatagramSocket by lazy {
        val selectorManager = ActorSelectorManager(Dispatchers.IO)

        val socketAddress: SocketAddress = InetSocketAddress("0.0.0.0", BROADCAST_PORT)

        aSocket(selectorManager).udp().bind(localAddress = socketAddress)
    }

    val channelFlow = callbackFlow {
        log("Listening on ${serverSocket.localAddress}")

        while (isActive) {
            val receive = serverSocket.receive()

            val text = receive.packet.readText()
            log("received: $text from: ${receive.address}")
            send(text)
        }

        awaitClose {
            serverSocket.close()
        }
    }

    suspend fun sendPackage(counter: Int) {
        val selectorManager = SelectorManager(Dispatchers.IO)
        val socketAddress: SocketAddress = InetSocketAddress("255.255.255.255", BROADCAST_PORT)

        val sendSockets = aSocket(selectorManager).udp().connect(socketAddress) {
            broadcast = true
        }

        log("Sending packet to: ${sendSockets.remoteAddress}")
        val datagram = Datagram(
            ByteReadPacket("Message number: $counter!!\n".encodeToByteArray()),
            sendSockets.remoteAddress
        )

        sendSockets.use {
            it.send(datagram)
        }
    }

}

fun log(message: String) {
    println("[Klardrop] $message")
}