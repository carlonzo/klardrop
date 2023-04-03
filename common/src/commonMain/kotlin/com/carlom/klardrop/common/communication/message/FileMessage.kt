package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.Serializable
import okio.Path

@Serializable
data class FileMessage(
  val fileName: String,
  val size: Int,
) : Message {
  override val type: MessageType = MessageType.FILE
  override val hasPayload: Boolean = true

  class SendRequest(
    override val message: Message,
    val pathFile: Path
  ) : SendMessageRequest
}

class FileMessageHandler(
  private val storePathProvider: () -> Path,
  private val serializer: MessageSerializer
) : MessageHandler<FileMessage, FileMessage.SendRequest> {

  override suspend fun handleIncoming(message: FileMessage, receiveChannel: ReceiveChannel<Frame>) {

    CurrentFileSystem.write(
      file = storePathProvider().resolve(message.fileName),
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

  override suspend fun handleOutgoing(request: FileMessage.SendRequest, sendChannel: SendChannel<Frame>) {

    val initialMessage = serializer.serialize(request.message)
    sendChannel.send(initialMessage)

    val path = request.pathFile
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