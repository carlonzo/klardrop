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

    private val serverSocket: BoundDatagramSocket by lazy {
        val selectorManager = ActorSelectorManager(Dispatchers.IO)

        val socketAddress: SocketAddress = InetSocketAddress("0.0.0.0", BROADCAST_PORT)

        aSocket(selectorManager).udp().bind(localAddress = socketAddress)
    }

    val channelFlow = callbackFlow<String> {


        val soc = serverSocket
        log("Listening on ${soc.localAddress}")

        while (true) {
            val receive = soc.receive()
            val text = receive.packet.readText()
            log("received: $text from: ${receive.address}")

            send(text)
        }


//        soc.incoming.receiveAsFlow().collect {
//
//            log("Received udp datagram from ${it.address}: $it ")
//
//            send(it.packet.readText())
//        }

        awaitClose {
//            soc.close()
        }
    }


    suspend fun sendPackage(counter: Int) {
        val address = InetSocketAddress("255.255.255.255", BROADCAST_PORT)

        val datagram = Datagram(
            ByteReadPacket("Message number: $counter!!".encodeToByteArray()),
            address
        )

        log("Sending '${datagram.packet.readText()}' packet to: ${datagram.address}")
        val socket = aSocket(ActorSelectorManager(Dispatchers.IO))
            .udp()
            .connect(address) { broadcast = true }

        socket.send(datagram)
        log("Closing socket")
        socket.close()
        log("Awaiting closed")
        socket.awaitClosed()
        log("Socket closed")

    }

}

fun log(message: String) {
    println("[Klardrop] $message")
}