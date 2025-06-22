package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.toDeviceType
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.getMimeTypeFromExtension
import com.carlom.klardrop.common.utils.log
import com.carlonzo.ukey2.Ukey2Handshake
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import com.google.location.nearby.connections.proto.*
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader.PayloadType
import com.google.security.cryptauth.lib.securegcm.DeviceType
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

      val msg = connectionRequest.toString()
      log("NearbyReceiverConnectionHandler", "Connection request received ${msg.substring(0, msg.length / 2)}")
      log("NearbyReceiverConnectionHandler", "Connection request received ${msg.substring(msg.length / 2)}")
      processConnectionRequest(connectionRequest)

      val nearbyConnection = createConnection(readChannel, writeChannel)
      log("NearbyReceiverConnectionHandler", "Handshake completed $nearbyConnection")

      makeIntroduction(writeChannel)

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

      // simulate user to accept the transfer
      delay(500)

      // just accept directly
      keepAliveWhileWaitingJob.cancel()
      // await the keepalive job is completed
      mutexKeepAlive.withLock { }

      acceptTransfer(nearbyConnection, writeChannel)

      receiveTransfer(readChannel, writeChannel, nearbyConnection)
    } catch (e: Exception) {
      log("NearbyReceiverConnectionHandler", "Error on connection", e)
      receiveFlow.update {
        it.copy(status = ReceiveMessageStatus.Failed(e.message ?: "Unknown error"))
      }
      throw e
    } finally {
      connection.close()
      log("NearbyReceiverConnectionHandler", "Connection closed")
    }

    require(receiveProgress.values.all { it == 100 }) { "Not all messages received $receiveProgress $messagesToReceive" }

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

            fileTransfer.onTransferCompleted()
            log("NearbyReceiverConnectionHandler", "File transfer completed")
            pendingTransfers.remove(payloadId)
          } else {
            processFileChunk(payload, fileTransfer)
          }

        } else if (message is TextMessage) {
          require(header.type == PayloadType.BYTES) { "Payload type is not bytes" }

          messagesToReceive[payloadId] = message.copy(
            text = message.text + payloadChunk.body!!.utf8()
          )

          if (payloadChunk.offset!! >= header.total_size!!) {
            log("NearbyReceiverConnectionHandler", "Text transfer completed")
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

    // Get the auth string
    val verificationString = server.getVerificationString(32)

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

    // send paired encryption
    // processConnectionResponseFrame
    log("NearbyReceiverConnectionHandler", "Sending paired encryption")

    val pairedKeyEncryptionFrame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION,
        paired_key_encryption = PairedKeyEncryptionFrame(
          secret_id_hash = Random.nextBytes(6).toByteString(),
          signed_data = Random.nextBytes(71).toByteString(), // neardrop uses 72 bytes but from tests we receive 71
        )
      )
    )

    sendEncryptedWrappedPayload(pairedKeyEncryptionFrame, writeChannel, nearbyConnection)

//    sentConnectionResponse done
    log("NearbyReceiverConnectionHandler", "Sent connection response")


    //receive keypair
    val keyPairSetupFrame = receiveTransferSetupFrame(nearbyConnection, readChannel, writeChannel)
    log("NearbyReceiverConnectionHandler", "Received keypair $keyPairSetupFrame")

//    processPairedKeyEncryptionFrame
    require(keyPairSetupFrame.v1?.paired_key_encryption != null) { "Paired key encryption not found" }

    val pairedResult = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.PAIRED_KEY_RESULT,
        paired_key_result = PairedKeyResultFrame(
          status = PairedKeyResultFrame.Status.UNABLE
        )
      )
    )

    sendEncryptedWrappedPayload(pairedResult, writeChannel, nearbyConnection)
    // state: sentPairedKeyResult


    // methods: processPairedKeyResultFrame
    // -- does nothing
    // state: receivedPairedKeyResult


    nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
      .let { offline ->
        val payload = offline.v1?.payload_transfer?.payload_chunk!!
        log("NearbyReceiverConnectionHandler", "Received key result: ${Frame.ADAPTER.decode(payload.body!!)}}")
      }


    log("NearbyReceiverConnectionHandler", "Sending paired encryption completed")

  }

  private fun processIntroductionFrame(
    frame: Frame
  ) {

    frame.v1?.introduction?.file_metadata?.forEach { fileMetadata ->

      messagesToReceive[fileMetadata.payload_id!!] = FileMessage(
        fileName = fileMetadata.name!!,
        fileSize = fileMetadata.size!!,
        mimeType = fileMetadata.mime_type ?: getMimeTypeFromExtension(fileMetadata.name)
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

    println("emitted progress $messagesProgress")

  }

  private suspend fun receiveTransferSetupFrame(
    nearbyConnection: D2DConnectionContext,
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel
  ): Frame {
    val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    println("receiveTransferSetupFrame $offlineFrame")

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
