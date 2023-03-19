package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.envelopes.Envelope
import com.carlom.klardrop.common.communication.envelopes.EnvelopeType
import com.carlom.klardrop.common.communication.envelopes.FileEnvelope
import com.carlom.klardrop.common.communication.envelopes.TextEnvelope
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import okio.Path

interface IncomingMessagesRouter {

  suspend fun onMessageReceived(fromDeviceId: String, envelope: Envelope, receiveChannel: ReceiveChannel<Frame>)

}

class IncomingMessagesRouterImpl(
  val handlers: Map<EnvelopeType, EnvelopeHandler<Envelope>>
) : IncomingMessagesRouter {

  override suspend fun onMessageReceived(fromDeviceId: String, envelope: Envelope, receiveChannel: ReceiveChannel<Frame>) {


    when (envelope) {
      is TextEnvelope -> log("Received text message from $fromDeviceId: ${envelope.text}")
      is FileEnvelope -> {
        handlers.get(envelope.type)?.handleIncoming(envelope, receiveChannel)
      }
    }

  }

}


interface EnvelopeHandler<E : Envelope> {

  suspend fun handleIncoming(envelope: E, receiveChannel: ReceiveChannel<Frame>)

  suspend fun handleOutgoing(envelope: E, sendChannel: SendChannel<Frame>)

}

class HandleFileEnvelope(
  private val storePath: Path
) : EnvelopeHandler<FileEnvelope> {

  @OptIn(DelicateCoroutinesApi::class)
  override suspend fun handleIncoming(envelope: FileEnvelope, receiveChannel: ReceiveChannel<Frame>) {

    CurrentFileSystem.write(
      file = storePath.resolve(envelope.fileName),
      mustCreate = true
    ) {

      while (!receiveChannel.isClosedForReceive) {

        val newFrame = receiveChannel.receive()
        write(newFrame.data)

        if (newFrame.fin) {
          break
        }

      }

    }

  }

  override suspend fun handleOutgoing(envelope: FileEnvelope, sendChannel: SendChannel<Frame>) {

    val path = storePath.resolve(envelope.fileName)
    CurrentFileSystem.read(path) {

      // maybe we can do a better job here with the buffer. read and write in parallel? read about okio.bugger

      while (!exhausted()) {

        val buffer = readByteArray(2048)

        val fin = exhausted()
        sendChannel.send(Frame.Binary(fin, buffer))
      }

    }

  }

}