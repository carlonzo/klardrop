package com.carlom.klardrop.common.communication.envelopes

import com.carlom.klardrop.common.persistence.CurrentFileSystem
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.Path

@Serializable
data class FileEnvelope(
  val fileName: String
) : Envelope.StreamingEnvelope {
  override val type: EnvelopeType = EnvelopeType.FILE
}

class FileEnvelopeHandler(
  private val storePath: Path
) : EnvelopeHandler<FileEnvelope> {

  override suspend fun handleIncoming(envelope: FileEnvelope, receiveChannel: ReceiveChannel<Frame>) {

    CurrentFileSystem.write(
      file = storePath.resolve(envelope.fileName),
      mustCreate = true
    ) {

      while (true) {

        val newFrame = receiveChannel.receive()
        write(newFrame.data)

        if (newFrame.fin) {
          break
        }

      }

    }

  }

  override suspend fun handleOutgoing(envelope: FileEnvelope, sendChannel: SendChannel<Frame>) {

// todo send envelope first

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