package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.utils.io.core.ByteReadPacket.Companion.Empty
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.invoke
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import okio.Buffer
import okio.BufferedSource
import okio.use
import kotlin.time.Duration.Companion.seconds

@Serializable
data class FileMessage(
  val fileName: String,
  val fileSize: Long,
  val mimeType: String
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
      log("FileMessageHandler", "Ready to receive file $message")

      val fileTransfer = fileManager.prepareSaveFile(
        fileName = message.fileName,
        mimeType = message.mimeType
      )

      log("FileMessageHandler", "Prepared file transfer for $message")

      var totalBytesReceived = 0

      runCatching {
        fileTransfer.bufferedSink.use {

          while (totalBytesReceived < message.fileSize) {

            log("FileMessageHandler", "Waiting to receive new frame for $message")
            val newFrame = withTimeout(5.seconds) {
              receiveChannel.receive()
            }

            val data = newFrame.data

            log("FileMessageHandler", "Received frame $newFrame")

            if (newFrame.fin && data.isEmpty()) {
              log("FileMessageHandler", "Received empty frame. Finishing")
              break
            }

            it.write(data)

            totalBytesReceived += data.size
            log("FileMessageHandler", "Received file frame of ${data.size}")
          }

          it.flush()
        }
      }.onSuccess {
        log("FileMessageHandler", "Received file with size: $totalBytesReceived")
        fileTransfer.onTransferCompleted()
      }.onFailure {
        log("FileMessageHandler", "Error while receiving file", it)
        fileTransfer.onTransferFailed()
        throw it
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


      val buffer = Buffer()
      var totalSent = 0L
      var frameCount = 0

      val start = clock.currentTimeMillis()
      runCatching {

        fileManager.getReadStreamFromUri(path).use { readBuffer ->
          while (!readBuffer.exhausted()) {

            readBuffer.fillBuffer(buffer, 106496) // TODO 106496b = 104kb looks like this is the size of the content of the raw frame sent. need to work more on this

            // closing the Frame with fin so the receiver can receive the full frame and flush to disk
            val flush = (frameCount % 5 == 0 && frameCount > 0) || readBuffer.exhausted()
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
        }
      }.onSuccess {
        log("FileMessageHandler", "File ${request.pathFile} sent successfully in ${clock.currentTimeMillis() - start} ms")
      }.onFailure {
        log("FileMessageHandler", "Error sending file", it)
        webSocketSession.send(Frame.Binary(true, Empty))
        webSocketSession.flush()
        buffer.close()

        throw it
      }
    }
  }


  private fun BufferedSource.fillBuffer(buffer: Buffer, size: Long) {

    do {
      val read = read(buffer, size)
    } while (read != -1L && buffer.size < size)

  }

  private suspend fun updateSentProgress(progress: MutableSharedFlow<MessengerSendProgress>, sent: Long, total: Long) {
    val progressValue = (sent * 100) / total
    progress.emit(MessengerSendProgress.InProgress(progressValue.toInt()))
    log("FileMessageHandler", "Sending update progress: $progressValue")
  }


}



