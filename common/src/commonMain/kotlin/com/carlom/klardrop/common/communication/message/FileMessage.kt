package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import io.ktor.util.cio.use
import io.ktor.utils.io.asByteWriteChannel
import io.ktor.utils.io.write
import io.ktor.utils.io.writeByteArray
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.invoke
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@Serializable
data class FileMessage(
  val fileName: String,
  val fileSize: Long,
  val mimeType: String
) : Message {
  override val type: MessageType = MessageType.FILE
  override val hasPayload: Boolean = true

  data class FileSendRequest(
    override val message: FileMessage,
    val file: PlatformFile
  ) : SendMessageRequest
}

fun FileMessage.toSendRequest(file: PlatformFile): FileMessage.FileSendRequest {
  return FileMessage.FileSendRequest(this, file)
}

class FileMessageHandler(
  private val serializer: MessageSerializer,
  private val fileManager: FileManager,
  private val clock: Clock,
  private val coroutines: Coroutines
) : MessageHandler<FileMessage, FileMessage.FileSendRequest> {

  override suspend fun handleIncoming(
    message: FileMessage,
    receiveChannel: ReceiveChannel<Frame>,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>
  ) {
    log("FileMessageHandler", "Receiving file $message")

    coroutines.ioDispatcher {
      log("FileMessageHandler", "Ready to receive file $message")

      receiveFlow.update {
        it.copy(
          messages = listOf(message),
          status = ReceiveMessageStatus.Started
        )
      }

      val fileTransfer = fileManager.prepareSaveFile(
        fileName = message.fileName,
        mimeType = message.mimeType
      )

      log("FileMessageHandler", "Prepared file transfer for $message")

      var totalBytesReceived = 0

      runCatching {
        fileTransfer.bufferedSink.use {

          receiveFlow.update {
            it.copy(
              status = ReceiveMessageStatus.Progress(listOf(message to 0))
            )
          }

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
            val progressValue = ((totalBytesReceived * 100L) / message.fileSize).toInt()

            log("FileMessageHandler", "Received total $totalBytesReceived / ${message.fileSize} :  Progress $progressValue %")

            receiveFlow.update {
              it.copy(
                status = ReceiveMessageStatus.Progress(listOf(message to progressValue.coerceIn(0, 100)))
              )
            }
          }
        }
      }.onSuccess {
        log("FileMessageHandler", "Received file with size: $totalBytesReceived")
        fileTransfer.onTransferCompleted()
        receiveFlow.update {
          it.copy(
            status = ReceiveMessageStatus.Completed
          )
        }
      }.onFailure { throwable ->
        log("FileMessageHandler", "Error while receiving file", throwable)
        fileTransfer.onTransferFailed()
        receiveFlow.update {
          it.copy(
            status = ReceiveMessageStatus.Failed(throwable.message ?: "Unknown error")
          )
        }
        throw throwable
      }
    }

  }

  override suspend fun handleOutgoing(
    request: FileMessage.FileSendRequest,
    webSocketSession: WebSocketSession,
    progressFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    coroutines.ioDispatcher.invoke {

      updateSentProgress(progressFlow, 0, request.message.fileSize)

      val initialMessage = serializer.serialize(request.message)
      webSocketSession.send(initialMessage)

      val sourceFile = request.file

      log("FileMessageHandler", "Sending file with path: $sourceFile")

      // Constants for optimal performance
      val chunkSize: Long = min(32 * 1024, webSocketSession.maxFrameSize) // 32KB chunks - good balance for WebSocket frames
      val flushInterval = 10 // Flush every 10 frames

      val buffer = Buffer()
      var totalSent = 0L
      var frameCount = 0

      val start = clock.currentTimeMillis()
      runCatching {

        fileManager.getReadStreamFrom(sourceFile).buffered().use { readBuffer ->
          while (!readBuffer.exhausted()) {
            buffer.clear()

            kotlin.runCatching {
              readBuffer.readAtMostTo(buffer, chunkSize)
            }.onFailure {
              log("FileMessageHandler", "Error while reading file", it)
              throw it
            }


            // closing the Frame with fin so the receiver can receive the full frame and flush to disk
            val bufferSize = buffer.size
            val isLastChunk = readBuffer.exhausted()

            webSocketSession.send(Frame.Binary(isLastChunk, buffer))

            totalSent += bufferSize
            updateSentProgress(progressFlow, totalSent, request.message.fileSize)

            frameCount += 1

            val shouldFlush = frameCount % flushInterval == 0
            if (shouldFlush) {
              webSocketSession.flush()
            }
          }

          webSocketSession.flush()
          buffer.close()
        }
      }.onSuccess {
        log("FileMessageHandler", "File ${request.file} sent successfully in ${clock.currentTimeMillis() - start} ms")
      }.onFailure {
        log("FileMessageHandler", "Error sending file", it)
        buffer.close()

        log("FileMessageHandler", "After error exists? ${request.file.exists()} ${request.file.size()} ${request.file.readBytes().size}")

        throw it
      }
    }
  }

  private suspend fun updateSentProgress(progress: MutableSharedFlow<MessengerSendProgress>, sent: Long, total: Long) {
    val progressValue = (sent * 100) / total
    progress.emit(MessengerSendProgress.InProgress(progressValue.toInt()))
    log("FileMessageHandler", "Sending update progress: $progressValue% - $sent / $total")
  }


}



