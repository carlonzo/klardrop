package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.toDeviceType
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.FileTypeUtils
import com.carlom.klardrop.common.utils.log
import com.carlonzo.ukey2.Ukey2Handshake
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import com.google.location.nearby.connections.proto.*
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader.PayloadType
import com.google.security.cryptauth.lib.securegcm.DeviceType
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import okio.ByteString.Companion.toByteString
import sharing.nearby.Frame
import sharing.nearby.PairedKeyEncryptionFrame
import sharing.nearby.PairedKeyResultFrame
import kotlin.random.Random

/**
 *  This class handles the nearby connection request.
 */
class NearbyReceiverConnectionHandler(
  private val fileManager: FileManager,
  coroutines: Coroutines,
  private val incomingAuthorizer: IncomingAuthorizer,
  private val messageRepository: MessageRepository,
) {

  private val messagesToReceive = mutableMapOf<Long, Message>()
  private val receiveProgress = mutableMapOf<Long, Int>()
  private lateinit var receiveFlow: MutableStateFlow<ReceiveMessageUpdate>

  private val connectionScope = coroutines.newScope(coroutines.ioDispatcher)

  suspend fun onConnection(
    connection: Socket,
    receiveFlow: MutableStateFlow<ReceiveMessageUpdate>,
    connectionRequest: OfflineFrame,
    readChannel: ByteReadChannel
  ) {

    this.receiveFlow = receiveFlow

    try {
      val writeChannel = connection.openWriteChannel(autoFlush = false)

      log("NearbyReceiverConnectionHandler", "Starting Nearby Share receive")
      processConnectionRequest(connectionRequest)

      val nearbyConnection = createConnection(readChannel, writeChannel)
      makeIntroduction(writeChannel)

      log("NearbyReceiverConnectionHandler", "Performing paired key exchange")
      handleKeyPairExchange(readChannel, writeChannel, nearbyConnection)

      handleTransferSetup(readChannel, writeChannel, nearbyConnection)

      // init messages to receive with 0 progress
      with(receiveProgress) {
        messagesToReceive.forEach { (id, _) ->
          put(id, 0)
        }
      }

      receiveFlow.update {
        it.copy(
          messages = messagesToReceive.values.toList()
        )
      }

      updateReceiveProgress()

      // create a job to keep alive while waiting for user to accept
      val mutexKeepAlive = Mutex()
      val keepAliveWhileWaitingJob = connectionScope.launch {

        while (isActive) {
          mutexKeepAlive.withLock {
            nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
          }
        }

      }

      // Ask the user to authorize the transfer. Trusted senders auto-accept; everyone
      // else gets a banner/chat prompt and we suspend here until they pick. Treat the
      // whole bundle as a FILE-kind transfer so files always prompt regardless of the
      // text-flows-after-first-contact rule (Nearby bundles can mix files and text).
      val deviceId = receiveFlow.value.device?.deviceId.orEmpty()
      val authorized = incomingAuthorizer.authorize(
        fromDeviceId = deviceId,
        kind = IncomingAuthorizer.TransferKind.FILE,
        headers = messagesToReceive.values.toList(),
        receiveFlow = receiveFlow,
      )

      keepAliveWhileWaitingJob.cancel()
      // await the keepalive job is completed
      mutexKeepAlive.withLock { }

      if (!authorized) {
        log("NearbyReceiverConnectionHandler", "User rejected Nearby transfer from $deviceId")
        rejectTransfer(nearbyConnection, writeChannel)
        return
      }

      acceptTransfer(nearbyConnection, writeChannel)

      log("NearbyReceiverConnectionHandler", "Starting file reception")
      receiveTransfer(readChannel, writeChannel, nearbyConnection)

      // Signal a clean end-of-session and drain the peer's own DISCONNECTION
      // before closing the socket. Mirrors NearDrop's receiver behavior; the
      // half-step is what lets the *other* side report success in its UI
      // instead of an error caused by an RST after our abrupt close.
      runCatching { sendDisconnection(writeChannel, nearbyConnection) }
        .onFailure { log("NearbyReceiverConnectionHandler", "Failed to send DISCONNECTION", it) }
      withTimeoutOrNull(3.seconds) {
        runCatching { nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel) }
      }
    } catch (e: Exception) {
      log("NearbyReceiverConnectionHandler", "Receive failed", e)
      receiveFlow.update {
        it.copy(status = ReceiveMessageStatus.Failed(e.message ?: "Unknown error"))
      }
      throw e
    } finally {
      connection.close()
    }

    require(receiveProgress.values.all { it == 100 }) { "Not all messages received $receiveProgress $messagesToReceive" }

    log("NearbyReceiverConnectionHandler", "Receive completed successfully")
    receiveFlow.update {
      it.copy(
        status = ReceiveMessageStatus.Completed,
        messages = messagesToReceive.values.toList()
      )
    }
  }

  private suspend fun receiveTransfer(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {

    val senderDeviceId = receiveFlow.value.device?.deviceId.orEmpty()

    // Pre-insert file_transfer rows for every incoming file so that the file message
    // row can reference its fileTransferId (mirrors FileMessageHandler.beginReceive).
    val fileTransferIds = buildMap<Long, Long> {
      messagesToReceive.forEach { (payloadId, message) ->
        if (message is FileMessage) {
          val fileTransferId = messageRepository.insertFileTransfer(
            fileName = message.fileName,
            filePath = "",
            totalSize = message.fileSize,
            status = FileTransferStatus.IN_PROGRESS,
            mimeType = message.mimeType,
          )
          put(payloadId, fileTransferId)
        }
      }
    }

    val fileTransfers = buildMap {

      messagesToReceive.forEach {
        if (it.value is FileMessage) {
          val fileMessage = it.value as FileMessage
          val fileTransfer = fileManager.prepareSaveFile(fileMessage.fileName, fileMessage.mimeType)

          put(it.key, fileTransfer)
        }
      }

    }

    // keeping payload ids to track pending transfers to be completed
    val pendingTransfers: MutableSet<Long> = messagesToReceive.keys.toMutableSet()

    fun areTransfersPending(): Boolean {
      return pendingTransfers.isNotEmpty()
    }

    log("NearbyReceiverConnectionHandler", "Start receiving transfer")
    while (areTransfersPending()) {
      // receive and wait until we get a filechunk

      val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
      if (offlineFrame.v1?.type == V1Frame.FrameType.DISCONNECTION) {
        throw IllegalStateException("Peer disconnected before all payloads were received")
      }
      if (offlineFrame.v1?.type == V1Frame.FrameType.PAYLOAD_TRANSFER) {

        log("NearbyReceiverConnectionHandler", "Payload transfer received ${offlineFrame.v1}")

        val payload = offlineFrame.v1?.payload_transfer
        require(payload != null) { "Payload not found" }

        val header = payload.payload_header
        require(header != null) { "Payload header not found" }

        val payloadChunk = payload.payload_chunk
        require(payloadChunk != null) { "Payload chunk body not found" }

        val payloadBody = payloadChunk.body

        val payloadId = header.id!!
        val message = messagesToReceive[payloadId]

        if (message is FileMessage) {
          require(header.type == PayloadType.FILE) { "Payload type is not file" }

          val fileTransfer = fileTransfers[payloadId]!!

          if (payloadBody == null || payloadBody.size == 0) {

            // Close (and thus flush) the buffered sink before finalising —
            // otherwise the last chunk's bytes are still sitting in the okio
            // buffer when onTransferCompleted reads the underlying storage.
            runCatching { fileTransfer.bufferedSink.close() }
            val finalPath = fileTransfer.onTransferCompleted()
            log("NearbyReceiverConnectionHandler", "File transfer completed")

            // Persist the received file to the database so it appears in chat
            // (mirrors FileMessageHandler.beginReceive + FileReceivePipeline.complete).
            val fileTransferId = fileTransferIds[payloadId]
            if (fileTransferId != null) {
              if (finalPath != null) {
                messageRepository.updateFileTransferFilePath(fileTransferId, finalPath.toString())
              }
              messageRepository.updateFileTransferStatus(fileTransferId, FileTransferStatus.COMPLETED)
              messageRepository.insertMessage(
                remoteDeviceId = senderDeviceId,
                content = message.fileName,
                isSender = false,
                messageType = PersistenceMessageType.FILE,
                fileTransferId = fileTransferId,
                isRead = false,
                mimeType = message.mimeType,
              )
            }

            pendingTransfers.remove(payloadId)
          } else {
            processFileChunk(payload, fileTransfer)
          }

        } else if (message is TextMessage) {
          require(header.type == PayloadType.BYTES) { "Payload type is not bytes" }

          val updatedText = message.text + payloadChunk.body!!.utf8()
          messagesToReceive[payloadId] = message.copy(text = updatedText)

          if (payloadChunk.offset!! >= header.total_size!!) {
            log("NearbyReceiverConnectionHandler", "Text transfer completed")

            // Persist the received text to the database so it appears in chat
            // (mirrors TextMessageHandler.handleIncoming on the Klardrop path).
            messageRepository.insertMessage(
              remoteDeviceId = senderDeviceId,
              content = updatedText,
              isSender = false,
              messageType = PersistenceMessageType.TEXT,
              isRead = false,
              mimeType = "text/plain",
            )

            pendingTransfers.remove(payloadId)
          }

        } else {
          log("NearbyReceiverConnectionHandler", "Unknown message with payloadId $payloadId")
          continue
        }

        // update progress

        if (payloadBody == null || payloadBody.size == 0) {
          receiveProgress[payloadId] = 100
        } else {
          val transferred = payloadChunk.offset!! + payloadBody.size
          val totalSize = header.total_size!!

          receiveProgress[payloadId] = (transferred * 100L / totalSize).toInt().coerceIn(0, 100)
        }

        updateReceiveProgress()
      }

    }

    log("NearbyReceiverConnectionHandler", "Transfer completed")
  }

  private fun processFileChunk(payloadTransfer: PayloadTransferFrame, fileTransfer: FileTransfer) {
    fileTransfer.bufferedSink.write(payloadTransfer.payload_chunk!!.body!!.toByteArray())
  }

  private suspend fun acceptTransfer(nearbyConnection: D2DConnectionContext, writeChannel: ByteWriteChannel) {
    log("NearbyReceiverConnectionHandler", "acceptTransfer")
    val frame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.RESPONSE,
        connection_response = sharing.nearby.ConnectionResponseFrame(
          status = sharing.nearby.ConnectionResponseFrame.Status.ACCEPT
        )
      )
    )

    sendEncryptedWrappedPayload(frame, writeChannel, nearbyConnection)
  }

  private suspend fun rejectTransfer(nearbyConnection: D2DConnectionContext, writeChannel: ByteWriteChannel) {
    log("NearbyReceiverConnectionHandler", "rejectTransfer")
    val frame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.RESPONSE,
        connection_response = sharing.nearby.ConnectionResponseFrame(
          status = sharing.nearby.ConnectionResponseFrame.Status.REJECT
        )
      )
    )

    sendEncryptedWrappedPayload(frame, writeChannel, nearbyConnection)
  }

  private suspend fun handleTransferSetup(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {
    // method: processIntroductionFrame
    val introductionFrame = receiveTransferSetupFrame(nearbyConnection, readChannel, writeChannel)

    processIntroductionFrame(introductionFrame)

    log("NearbyReceiverConnectionHandler", "handleTransferSetup completed")
  }

  private fun processConnectionRequest(connectionRequest: OfflineFrame) {
    val endpointInfo =
      connectionRequest.v1?.connection_request?.endpoint_info?.toByteArray() ?: throw IllegalStateException("Endpoint info not found")
    require(endpointInfo.size > 17) { "Endpoint info is too short" }

    val deviceNameLength = endpointInfo[17].toInt()
    val deviceName = endpointInfo.copyOfRange(18, 18 + deviceNameLength).decodeToString()
    val deviceType = DeviceType.fromValue((endpointInfo[0].toInt() and 7) shr 1)

    receiveFlow.update {
      it.copy(
        device = DeviceInfo(
          deviceId = it.device?.deviceId ?: "",
          name = deviceName,
          deviceType = deviceType.toDeviceType()
        )
      )
    }

    log("NearbyReceiverConnectionHandler", "Connection request from $deviceName ($deviceType)")
  }


  private suspend fun createConnection(readChannel: ByteReadChannel, writeChannel: ByteWriteChannel): D2DConnectionContext {
    val server = Ukey2Handshake.forResponder(Ukey2Handshake.HandshakeCipher.P256_SHA512)

    // Message 1 (Client Init)
    val handshakeMessage = readChannel.readByteArray()
    server.parseHandshakeMessage(handshakeMessage)
    log("NearbyReceiverConnectionHandler", "Client Init received")

    // Message 2 (Server Init)
    val nextHandshakeMessage = server.getNextHandshakeMessage()
    writeChannel.writeFullyNearby(nextHandshakeMessage)
    log("NearbyReceiverConnectionHandler", "Server Init sent")

    // Message 3 (Client Finish)
    val clientHandshake = readChannel.readByteArray()
    server.parseHandshakeMessage(clientHandshake)
    log("NearbyReceiverConnectionHandler", "Client Finish received")

    // Get the auth string (used for PIN verification)
    server.getVerificationString(32)

    // lets accept everything
    server.verifyHandshake()

    // read connection response
    val clientKey = readChannel.readByteArray()
    val messageClientKeyMessage = OfflineFrame.ADAPTER.decode(clientKey)

    require(messageClientKeyMessage.v1?.type == V1Frame.FrameType.CONNECTION_RESPONSE) { "Connection response not found" }
    require(messageClientKeyMessage.v1?.connection_response?.response == ConnectionResponseFrame.ResponseStatus.ACCEPT) { "Connection response not accepted" }

    log("NearbyReceiverConnectionHandler", "Handshake completed")
    return server.toConnectionContext()
  }

  private suspend fun makeIntroduction(
    writeChannel: ByteWriteChannel
  ) {

    // accept request
    log("NearbyReceiverConnectionHandler", "Accepting connection")
    OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.CONNECTION_RESPONSE,
        connection_response = ConnectionResponseFrame(
          response = ConnectionResponseFrame.ResponseStatus.ACCEPT,
          status = 0,
          os_info = OsInfo(
            type = CommonPlatformDependencies.osType().toOsInfo()
          )
        )
      )
    ).send(writeChannel)

    // from now on the messages are encrypted
  }

  private suspend fun handleKeyPairExchange(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {

    // Step 1: Send server's PairedKeyEncryptionFrame (SERVER SENDS FIRST per protocol line 97 & Swift lines 257-260)
    val serverPairedKeyEncryptionFrame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION,
        paired_key_encryption = PairedKeyEncryptionFrame(
          secret_id_hash = Random.nextBytes(6).toByteString(),
          signed_data = Random.nextBytes(72).toByteString(),
        )
      )
    )
    sendEncryptedWrappedPayload(serverPairedKeyEncryptionFrame, writeChannel, nearbyConnection)

    // Step 2: Receive client's PairedKeyEncryptionFrame (CLIENT SENDS SECOND per protocol line 98 & Swift lines 83-84, 266)
    val clientKeyPairSetupFrame = receiveTransferSetupFrame(nearbyConnection, readChannel, writeChannel)
    require(clientKeyPairSetupFrame.v1?.paired_key_encryption != null) { "Client's paired key encryption not found" }

    // Step 3: Send server's PairedKeyResultFrame (SERVER SENDS FIRST per protocol line 99 & Swift lines 268-275)
    val serverPairedResult = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.PAIRED_KEY_RESULT,
        paired_key_result = PairedKeyResultFrame(
          status = PairedKeyResultFrame.Status.UNABLE
        )
      )
    )
    sendEncryptedWrappedPayload(serverPairedResult, writeChannel, nearbyConnection)

    // Step 4: Receive client's PairedKeyResultFrame (CLIENT SENDS SECOND per protocol line 100 & Swift lines 85-86, 278)
    val clientPairedKeyResultOffline = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    val clientPairedKeyResultPayload = clientPairedKeyResultOffline.v1?.payload_transfer?.payload_chunk!!
    Frame.ADAPTER.decode(clientPairedKeyResultPayload.body!!)

  }

  private fun processIntroductionFrame(
    frame: Frame
  ) {

    frame.v1?.introduction?.file_metadata?.forEach { fileMetadata ->

      messagesToReceive[fileMetadata.payload_id!!] = FileMessage(
        fileName = fileMetadata.name!!,
        fileSize = fileMetadata.size!!,
        mimeType = fileMetadata.mime_type ?: FileTypeUtils.getMimeTypeFromExtension(fileMetadata.name)
      )

    }

    frame.v1?.introduction?.text_metadata?.forEach { textMetadata ->

      messagesToReceive[textMetadata.payload_id!!] = TextMessage(
        title = textMetadata.text_title ?: "",
        text = ""
      )

    }

    log("NearbyReceiverConnectionHandler", "Messages ready to be received: $messagesToReceive")

  }

  private fun updateReceiveProgress() {

    val messagesProgress = receiveProgress.map {
      messagesToReceive[it.key]!! to it.value
    }

    receiveFlow.update {
      it.copy(
        status = ReceiveMessageStatus.Progress(
          messagesProgress
        )
      )
    }

    log("NearbyReceiverConnectionHandler", "emitted progress $messagesProgress")

  }

  private suspend fun receiveTransferSetupFrame(
    nearbyConnection: D2DConnectionContext,
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel
  ): Frame {
    val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    log("NearbyReceiverConnectionHandler", "receiveTransferSetupFrame $offlineFrame")

    val payloadTransfer = offlineFrame.v1?.payload_transfer
    require(payloadTransfer != null) { "Payload transfer not found" }

    val header = payloadTransfer.payload_header
    require(header != null) { "Payload header not found" }
    require(header.type == PayloadType.BYTES) { "Payload type is not bytes" }

    val bodyPayload = payloadTransfer.payload_chunk?.body
    require(bodyPayload != null) { "Payload body not found" }
    return Frame.ADAPTER.decode(bodyPayload.toByteArray())
  }

}
