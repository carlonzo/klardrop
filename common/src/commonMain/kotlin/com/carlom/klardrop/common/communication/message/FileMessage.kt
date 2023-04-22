package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.serialization.Serializable
import okio.Buffer
import okio.BufferedSource
import okio.use

@Serializable
data class FileMessage(
  val fileName: String,
  val fileSize: Long,
  val mimeType: String? = null
) : Message {
  override val type: MessageType = MessageType.FILE
  override val hasPayload: Boolean = true

  class SendRequest(
    override val message: FileMessage,
    val pathFile: String
  ) : SendMessageRequest
}

fun FileMessage.toSendRequest(filePath: String): FileMessage.SendRequest {
  return FileMessage.SendRequest(this, filePath)
}

class FileMessageHandler(
  private val serializer: MessageSerializer,
  private val fileManager: FileManager,
  private val clock: Clock,
  private val coroutines: Coroutines
) : MessageHandler<FileMessage, FileMessage.SendRequest> {

  override suspend fun handleIncoming(message: FileMessage, receiveChannel: ReceiveChannel<Frame>) {
    log("FileMessageHandler", "Receiving file $message")

    coroutines.ioDispatcher.invoke {
      val fileTransfer = fileManager.prepareSaveFile(
        fileName = message.fileName,
      )

      runCatching {
        fileTransfer.bufferedSink.use {

          var totalBytesReceived = 0
          while (totalBytesReceived < message.fileSize) {
            val newFrame = receiveChannel.receive()
            val data = newFrame.data
            it.write(data)

            totalBytesReceived += data.size
            log("FileMessageHandler", "Received file frame of ${data.size}")
          }

          it.flush()
          log("FileMessageHandler", "Received file with size: $totalBytesReceived")
        }
      }.onSuccess {
        fileTransfer.onTransferCompleted()
      }.onFailure {
        fileTransfer.onTransferFailed()
      }
    }

  }

  override suspend fun handleOutgoing(
    request: FileMessage.SendRequest,
    webSocketSession: WebSocketSession,
    progressFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    coroutines.ioDispatcher.invoke {

      updateSentProgress(progressFlow, 0, request.message.fileSize)

      val initialMessage = serializer.serialize(request.message)
      webSocketSession.send(initialMessage)

      val path = request.pathFile

      log("FileMessageHandler", "Sending file with path: $path")


      fileManager.getReadStreamFromUri(path).use {

        val buffer = Buffer()
        var totalSent = 0L
        var frameCount = 0

        val start = clock.currentTimeMillis()
        runCatching {
          while (!it.exhausted()) {

            it.fillBuffer(buffer, 1_00_000)

            // closing the Frame with fin so the receiver can receive the full frame and flush to disk
            val flush = (frameCount % 5 == 0 && frameCount > 0) || it.exhausted()
            val bufferSize = buffer.size

            webSocketSession.send(Frame.Binary(flush, buffer.readByteArray()))

            if (flush) {
              webSocketSession.flush()
            }

            totalSent += bufferSize
            updateSentProgress(progressFlow, totalSent, request.message.fileSize)

            frameCount += 1
          }

          webSocketSession.flush()
        }.onFailure {
          log("FileMessageHandler", "Error sending file", it)
        }.onSuccess {
          log("FileMessageHandler", "File ${request.pathFile} sent successfully in ${clock.currentTimeMillis() - start} ms")
        }

        buffer.close()
      }
    }
  }

  private fun BufferedSource.fillBuffer(buffer: Buffer, size: Long) {

    do {
      val read = read(buffer, size)
    } while (read != -1L && buffer.size < size)

  }

  private suspend fun updateSentProgress(progress: MutableSharedFlow<MessengerSendProgress>, sent: Long, total: Long) {
    val progressValue = (sent * 100.0) / total
    progress.emit(MessengerSendProgress.InProgress(progressValue.toFloat()))
    log("FileMessageHandler", "Sending update progress: $progressValue")
  }


}



