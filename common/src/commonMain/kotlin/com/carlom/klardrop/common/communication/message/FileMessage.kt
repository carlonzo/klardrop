package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.sendMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.invoke
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@Serializable
data class FileMessage(
  val fileName: String,
  val fileSize: Long,
  val mimeType: String,
  override val id: Int = kotlin.random.Random.nextInt()
) : Message() {
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
    readChannel: ByteReadChannel,
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

            log("FileMessageHandler", "Waiting to receive data for $message")
            val chunkSize = min(32 * 1024, (message.fileSize - totalBytesReceived).toInt())
            val data = ByteArray(chunkSize)

            val bytesRead = withTimeout(5.seconds) {
              readChannel.readFully(data, 0, chunkSize)
              chunkSize
            }

            log("FileMessageHandler", "Received $bytesRead bytes")

            if (bytesRead == 0) {
              log("FileMessageHandler", "No more data. Finishing")
              break
            }

            it.write(data, 0, bytesRead)

            totalBytesReceived += bytesRead
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
    writeChannel: ByteWriteChannel,
    progressFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    coroutines.ioDispatcher.invoke {

      updateSentProgress(progressFlow, 0, request.message.fileSize)

      // Send initial message with metadata
      writeChannel.sendMessage(request.message, serializer)

      val sourceFile = request.file

      log("FileMessageHandler", "Sending file with path: $sourceFile")

      // Constants for optimal performance  
      val chunkSize = 32 * 1024 // 32KB chunks
      val buffer = ByteArray(chunkSize)
      var totalSent = 0L

      val start = clock.currentTimeMillis()
      runCatching {

        fileManager.getReadStreamFrom(sourceFile).buffered().use { readBuffer ->
          while (!readBuffer.exhausted()) {
            val bytesToRead = min(chunkSize.toLong(), request.message.fileSize - totalSent).toInt()
            val bytesRead = readBuffer.readAtMostTo(buffer, 0, bytesToRead)

            if (bytesRead <= 0) break

            writeChannel.writeFully(buffer, 0, bytesRead)

            totalSent += bytesRead
            updateSentProgress(progressFlow, totalSent, request.message.fileSize)

            log("FileMessageHandler", "Sent $totalSent / ${request.message.fileSize} bytes")
          }
        }
      }.onSuccess {
        log("FileMessageHandler", "File ${request.file} sent successfully in ${clock.currentTimeMillis() - start} ms")
      }.onFailure {
        log("FileMessageHandler", "Error sending file", it)

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



