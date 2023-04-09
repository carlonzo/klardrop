package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.persistence.CurrentFileSystem
import com.carlom.klardrop.common.utils.FileResolver
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.Serializable
import okio.Buffer
import okio.BufferedSource
import okio.Path
import okio.use

@Serializable
data class FileMessage(
  val fileName: String,
  val size: Long,
  val mimeType: String? = null
) : Message {
  override val type: MessageType = MessageType.FILE
  override val hasPayload: Boolean = true

  class SendRequest(
    override val message: Message,
    val pathFile: String
  ) : SendMessageRequest
}

fun FileMessage.toSendRequest(filePath: String): FileMessage.SendRequest {
  return FileMessage.SendRequest(this, filePath)
}

class FileMessageHandler(
  private val storePathProvider: () -> Path,
  private val serializer: MessageSerializer,
  private val fileResolver: FileResolver
) : MessageHandler<FileMessage, FileMessage.SendRequest> {

  override suspend fun handleIncoming(message: FileMessage, receiveChannel: ReceiveChannel<Frame>) {

    val destinationPath = storePathProvider().resolve(message.fileName)
    log("FileMessage", "Receiving file $message and saving to $destinationPath")

    CurrentFileSystem.write(
      file = destinationPath,
      mustCreate = true
    ) {

      log("FileMessage", "Writing file $message into $destinationPath")

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
    val bufferedSource = fileResolver.getReadStreamFromUri(path)

    log("FileMessage", "Sending file with path: $path")
    var counter = 1

    bufferedSource.use {

      val buffer = Buffer()
      while (!it.exhausted()) {

        it.fillBuffer(buffer, 500_000)

        log("FileMessage", "Sending file frame counter: $counter - ${buffer.size} bytes")

        val fin = it.exhausted()
        sendChannel.send(Frame.Binary(fin, buffer.readByteArray()))

        buffer.clear()
        counter += 1
      }

      buffer.close()
    }

    log("FileMessage", "File sent with: $path")
  }

  private fun BufferedSource.fillBuffer(buffer: Buffer, size: Long) {

    do {
      val read = read(buffer, size)
    } while (read != -1L && buffer.size < size)

  }

}