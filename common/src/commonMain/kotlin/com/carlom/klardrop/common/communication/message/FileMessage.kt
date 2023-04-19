package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.FileResolver
import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.Serializable
import okio.*

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
  private val storePathProvider: () -> Path,
  private val serializer: MessageSerializer,
  private val fileResolver: FileResolver,
  private val fileSystem: FileSystem,
  private val clock: Clock,
) : MessageHandler<FileMessage, FileMessage.SendRequest> {

  override suspend fun handleIncoming(message: FileMessage, receiveChannel: ReceiveChannel<Frame>) {

    val rootFolder = storePathProvider()
    val destinationPath = rootFolder.resolve(message.fileName)
    log("FileMessageHandler", "Receiving file $message and saving to $destinationPath")

    var newDestinationPath = destinationPath
    while (fileSystem.exists(newDestinationPath)) {
      newDestinationPath = generateNewFilePath(rootFolder, newDestinationPath)
    }

    fileSystem.write(
      file = newDestinationPath,
      mustCreate = true
    ) {

      log("FileMessageHandler", "Writing file $message into $newDestinationPath with size ${message.fileSize}")

      var totalBytesReceived = 0
      while (totalBytesReceived < message.fileSize) {
        val newFrame = receiveChannel.receive()
        val data = newFrame.data
        write(data)

        totalBytesReceived += data.size
        log("FileMessageHandler", "Received file frame of ${data.size}")
      }

      flush()
      log("FileMessageHandler", "Received file with size: $totalBytesReceived")
    }

  }

  override suspend fun handleOutgoing(
    request: FileMessage.SendRequest,
    webSocketSession: WebSocketSession,
    progressFlow: MutableSharedFlow<MessengerSendProgress>
  ) {

    updateSentProgress(progressFlow, 0, request.message.fileSize)

    val initialMessage = serializer.serialize(request.message)
    webSocketSession.send(initialMessage)

    val path = request.pathFile
    val bufferedSource = fileResolver.getReadStreamFromUri(path)

    log("FileMessageHandler", "Sending file with path: $path")

    val sendScope = CoroutineScope(Dispatchers.IO)
    val flushJobs = mutableListOf<Deferred<Unit>>()

    bufferedSource.use {

      val buffer = Buffer()
      var totalSent = 0L

      var frameCount = 0

      val start = clock.currentTimeMillis()
      runCatching {
        while (!it.exhausted()) {

          it.fillBuffer(buffer, 1_000_000)

          // closing the Frame with fin so the receiver can receive the full frame and flush to disk
          val flush = (frameCount % 5 == 0 && frameCount > 0) || it.exhausted()
          val bufferSize = buffer.size

          if (flush) log("FileMessageHandler", "Sending file frame : ${buffer.size} bytes")
          webSocketSession.send(Frame.Binary(flush, buffer.readByteArray()))

          if (flush) {
//            sendScope
//              .async { webSocketSession.flush() }
//              .let { flushJobs.add(it) }
            webSocketSession.flush()
          }

          totalSent += bufferSize
          updateSentProgress(progressFlow, totalSent, request.message.fileSize)

          frameCount += 1
        }
        log("FileMessageHandler", "Flushing web socket session after last frame")

        webSocketSession.flush()
        flushJobs.awaitAll()
        sendScope.cancel()

        log("FileMessageHandler", "Flushing web socket session completed")
      }.onFailure {
        log("FileMessageHandler", "Error sending file", it)
      }.onSuccess {
        log("FileMessageHandler", "File ${request.pathFile} sent successfully in ${clock.currentTimeMillis() - start} ms")
      }

      buffer.close()
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

  private fun generateNewFilePath(parentPath: Path, path: Path): Path {
    val regex = ".+\\((\\d+)\\)".toRegex() // "file (1).txt"
    val fileNameWithExtension = path.name
    val extension = fileNameWithExtension.substringAfterLast(".", "")
    val fileName = fileNameWithExtension.removeSuffix(".$extension")

    val match = regex.find(fileName)

    if (match == null) {
      return parentPath.resolve("$fileName (1).$extension")
    } else {
      match.groups[1]?.value?.toInt()?.let {
        val newNumber = it + 1
        val newFileName = fileName.replace("($it)", "($newNumber)")
        return parentPath.resolve("$newFileName.$extension")
      } ?: run {
        return parentPath.resolve("$fileName (1).$extension")
      }
    }

  }

}



