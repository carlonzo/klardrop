package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.MessengerSendProgress.*
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SignedSendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.FileTypeUtils
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.toByteArray
import com.carlonzo.ukey2.Ukey2Handshake
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import com.google.location.nearby.connections.proto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.MediumMetadata
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.OsInfo
import com.google.location.nearby.connections.proto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.V1Frame
import com.google.location.nearby.connections.proto.WifiLanUsableChannels
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.buffered
import okio.ByteString.Companion.toByteString
import sharing.nearby.ConnectionResponseFrame.Status
import sharing.nearby.FileMetadata
import sharing.nearby.Frame
import sharing.nearby.IntroductionFrame
import sharing.nearby.PairedKeyEncryptionFrame
import sharing.nearby.PairedKeyResultFrame
import sharing.nearby.TextMetadata
import sharing.nearby.V1Frame.FrameType
import kotlin.random.Random

class NearbyClientConnectionHandler(
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val fileManager: FileManager,
  private val sendRequests: List<SendMessageRequest>,
) {

  // transfer requests id -> SendMessageRequest
  private val transfers = sendRequests.associateBy { Random.nextLong() }

  suspend fun onConnection(connection: Socket, sendFlow: MutableSharedFlow<MessengerSendProgress>) {

    log("NearbyClientConnectionHandler", "═══════════════════════════════════════════════════════")
    log("NearbyClientConnectionHandler", "Starting connection to transfer $sendRequests using Nearby protocol")
    log("NearbyClientConnectionHandler", "═══════════════════════════════════════════════════════")

    sendFlow.emit(MessengerSendProgress.Pending)

    try {
      val readChannel = connection.openReadChannel()
      val writeChannel = connection.openWriteChannel(autoFlush = false)

      log("NearbyClientConnectionHandler", "STEP 1: Sending connection request")
      sendConnectionRequest(writeChannel)
      log("NearbyClientConnectionHandler", "STEP 1 DONE: Connection request sent")

      log("NearbyClientConnectionHandler", "STEP 2: Creating UKEY2 connection")
      val nearbyConnection = createConnection(readChannel, writeChannel)
      log("NearbyClientConnectionHandler", "STEP 2 DONE: UKEY2 connection created")

      log("NearbyClientConnectionHandler", "STEP 3: Making introduction")
      makeIntroduction(readChannel)
      log("NearbyClientConnectionHandler", "STEP 3 DONE: Introduction completed")

      log("NearbyClientConnectionHandler", "STEP 4: Handling key pair exchange")
      handleKeyPairExchange(readChannel, writeChannel, nearbyConnection)
      log("NearbyClientConnectionHandler", "STEP 4 DONE: Key pair exchange completed")

      log("NearbyClientConnectionHandler", "STEP 5: Sending transfer setup (introduction frame)")
      handleTransferSetup(writeChannel, nearbyConnection)
      log("NearbyClientConnectionHandler", "STEP 5 DONE: Transfer setup sent")

      log("NearbyClientConnectionHandler", "STEP 6: Waiting for receiver to accept transfer")
      waitForTransferResponse(readChannel, writeChannel, nearbyConnection)
      log("NearbyClientConnectionHandler", "STEP 6 DONE: Transfer accepted by receiver")

      log("NearbyClientConnectionHandler", "STEP 7: Initiating file transfer")
      initiateTransfer(writeChannel, nearbyConnection, sendFlow)
      log("NearbyClientConnectionHandler", "STEP 7 DONE: File transfer completed")

    } catch (e: Exception) {
      log("NearbyClientConnectionHandler", "❌ ERROR: Transfer failed with exception", e)
      sendFlow.emit(MessengerSendProgress.Error(e.message ?: "Unknown error"))
      throw e
    } finally {
      connection.close()
      log("NearbyClientConnectionHandler", "Connection closed")
    }

    log("NearbyClientConnectionHandler", "✅ Transfer completed successfully")
    sendFlow.emit(Completed)

  }

  private suspend fun initiateTransfer(
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext,
    sendFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    log("initiateTransfer", "  Sending unknown initial payload (protocol handshake data)")

    // unknown first payload
    sendEncryptedWrappedPayload(
      payload = listOf(8, 1, 18, 11, 8, 7, 58, 7, 13, 0, 0, 0, 0, 16, 1).toByteArray(),
      writeChannel = writeChannel,
      nearbyConnection = nearbyConnection
    )
    log("initiateTransfer", "  Unknown initial payload sent")

    log("initiateTransfer", "  Starting transfer of ${transfers.size} item(s)")

    transfers.forEach {
      val id = it.key

      when (val request = it.value) {

        is SimpleSendMessageRequest -> {
          val textMessage = request.message as TextMessage
          log("initiateTransfer", "  📝 Transferring text message (payload_id=$id): ${textMessage.text.take(50)}...")

          sendEncryptedWrappedPayload(
            payload = textMessage.text.toByteArray(),
            payloadType = PayloadTransferFrame.PayloadHeader.PayloadType.BYTES,
            payloadId = id,
            writeChannel = writeChannel,
            nearbyConnection = nearbyConnection
          )
          log("initiateTransfer", "  ✅ Text message transfer completed (payload_id=$id)")
        }

        is FileMessage.FileSendRequest -> {
          log("initiateTransfer", "  📁 Transferring file (payload_id=$id): ${request.message.fileName} (${request.message.fileSize} bytes)")

          fileManager.getReadStreamFrom(request.file).buffered().use { source ->

            sendEncryptedWrappedPayload(
              source = source,
              totalSize = request.message.fileSize,
              payloadType = PayloadTransferFrame.PayloadHeader.PayloadType.FILE,
              payloadId = id,
              writeChannel = writeChannel,
              nearbyConnection = nearbyConnection
            ).collect { progress ->
              log("initiateTransfer", "  📊 File transfer progress (payload_id=$id): $progress%")
              sendFlow.emit(InProgress(progress))
            }

          }
          log("initiateTransfer", "  ✅ File transfer completed (payload_id=$id)")

        }

        is SignedSendMessageRequest -> error("SignedSendMessageRequest is not supported in Nearby transfers ")
      }
    }

    log("initiateTransfer", "  All transfers completed successfully")

  }

  private suspend fun waitForTransferResponse(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {

    log("waitForTransferResponse", "  Waiting for receiver to accept/reject the transfer...")

    while (true) {

      sendKeepAlive(writeChannel, nearbyConnection)
      log("waitForTransferResponse", "  Sent keep-alive, waiting for response...")

      val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)

      if (offlineFrame.v1?.type == V1Frame.FrameType.KEEP_ALIVE) {
        log("waitForTransferResponse", "  Received keep-alive, continuing to wait...")
        delay(5000)
        continue
      }

      val frame = extractPayloadFromOfflineFrame(offlineFrame, Frame.ADAPTER)
      val frameType = frame.v1?.type ?: throw IllegalStateException("Frame type not found $offlineFrame $frame")

      log("waitForTransferResponse", "  Received frame type: $frameType")

      if (frameType == FrameType.RESPONSE) {

        if (frame.v1?.connection_response?.status == Status.ACCEPT) {
          log("waitForTransferResponse", "  ✅ Transfer ACCEPTED by receiver!")
          break
        } else {
          log("waitForTransferResponse", "  ❌ Transfer REJECTED by receiver: ${frame.v1?.connection_response?.status}")
          throw IllegalStateException("Transfer rejected from the receiver $frame")
        }

      }

    }

  }

  private suspend fun handleTransferSetup(writeChannel: ByteWriteChannel, nearbyConnection: D2DConnectionContext) {

    val fileMetadatas = transfers.filterValues { it is FileMessage.FileSendRequest }.map { (id, request) ->

      request as FileMessage.FileSendRequest

      val mimetype = request.message.mimeType.lowercase()

      val fileType: FileMetadata.Type =
        if (FileTypeUtils.isImageMimeType(mimetype)) FileMetadata.Type.IMAGE
        else if (FileTypeUtils.isVideo(mimetype)) FileMetadata.Type.VIDEO
        else if (FileTypeUtils.isAudioMimeType(mimetype)) FileMetadata.Type.AUDIO
        else FileMetadata.Type.UNKNOWN

      FileMetadata(
        name = request.message.fileName,
        type = fileType,
        payload_id = id,
        size = request.message.fileSize,
        mime_type = mimetype,
        id = id
      )
    }
    val textMetadatas = transfers.filterValues { it is SimpleSendMessageRequest }.map { (id, request) ->

      request as SimpleSendMessageRequest

      TextMetadata(
        payload_id = id,
        id = id,
        text_title = null,
        type = TextMetadata.Type.TEXT,
        size = (request.message as TextMessage).text.length.toLong()
      )
    }

    log("NearbyClientConnectionHandler", "  Preparing introduction frame with ${fileMetadatas.size} files and ${textMetadatas.size} text messages")

    val introductionTransferFrame = sharing.nearby.V1Frame(
      type = FrameType.INTRODUCTION,
      introduction = IntroductionFrame(
        file_metadata = fileMetadatas,
        text_metadata = textMetadatas,
        wifi_credentials_metadata = emptyList(),
      )
    )

    val frame = Frame(
      version = Frame.Version.V1,
      v1 = introductionTransferFrame
    )

    log("NearbyClientConnectionHandler", "  Sending introduction frame: $frame")
    sendEncryptedWrappedPayload(frame, writeChannel, nearbyConnection)
    log("NearbyClientConnectionHandler", "  Introduction frame sent (with automatic LAST_CHUNK)")
  }

  private suspend fun handleKeyPairExchange(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {
    log("NearbyClientConnectionHandler", "  Sub-step 4.1: Waiting for server's PairedKeyEncryptionFrame (SERVER SENDS FIRST)")

    // Step 1: Receive server's PairedKeyEncryptionFrame (SERVER SENDS FIRST per protocol diagram line 97)
    val serverKeyEncryptionFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    log("NearbyClientConnectionHandler", "  Sub-step 4.1 DONE: Received server's PairedKeyEncryptionFrame: $serverKeyEncryptionFrame")

    log("NearbyClientConnectionHandler", "  Sub-step 4.2: Sending client's PairedKeyEncryptionFrame")

    // Step 2: Send client's PairedKeyEncryptionFrame (CLIENT SENDS SECOND per protocol diagram line 98)
    val clientKeyEncryptionFrame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = FrameType.PAIRED_KEY_ENCRYPTION,
        paired_key_encryption = PairedKeyEncryptionFrame(
          secret_id_hash = Random.nextBytes(6).toByteString(),
          signed_data = Random.nextBytes(72).toByteString(),
        )
      )
    )
    sendEncryptedWrappedPayload(clientKeyEncryptionFrame, writeChannel, nearbyConnection)
    log("NearbyClientConnectionHandler", "  Sub-step 4.2 DONE: Sent client's PairedKeyEncryptionFrame")

    log("NearbyClientConnectionHandler", "  Sub-step 4.3: Waiting for server's PairedKeyResultFrame (SERVER SENDS FIRST)")

    // Step 3: Receive server's PairedKeyResultFrame (SERVER SENDS FIRST per protocol diagram line 99)
    val receivedServerPairedKeyResult = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    log("NearbyClientConnectionHandler", "  Sub-step 4.3 DONE: Received server's PairedKeyResultFrame: $receivedServerPairedKeyResult")

    log("NearbyClientConnectionHandler", "  Sub-step 4.4: Sending client's PairedKeyResultFrame")

    // Step 4: Send client's PairedKeyResultFrame (CLIENT SENDS SECOND per protocol diagram line 100)
    val clientExchangeResponse = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = FrameType.PAIRED_KEY_RESULT,
        paired_key_result = PairedKeyResultFrame(
          status = PairedKeyResultFrame.Status.UNABLE
        )
      )
    )
    sendEncryptedWrappedPayload(clientExchangeResponse, writeChannel, nearbyConnection)
    log("NearbyClientConnectionHandler", "  Sub-step 4.4 DONE: Sent client's PairedKeyResultFrame")

    log("NearbyClientConnectionHandler", "  Paired key exchange completed successfully")
  }

  private suspend fun sendConnectionRequest(writeChannel: ByteWriteChannel) {
    log("NearbyClientConnectionHandler", "Sending sendConnectionRequest")
    val currentDevice = currentDeviceProvider.get()

    val endpointInfo = createEndpointInfo(currentDevice)

    val chars = listOf(('0'..'9'), ('A'..'Z')).flatten()
    val endpointId = "${chars.random()}${chars.random()}${chars.random()}${chars.random()}"

    OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.CONNECTION_REQUEST,
        connection_request = ConnectionRequestFrame(
          endpoint_id = endpointId,
          endpoint_info = endpointInfo.toByteString(),
          endpoint_name = endpointInfo.toByteString().toString(),
          mediums = listOf(ConnectionRequestFrame.Medium.WIFI_LAN),
          medium_metadata = MediumMetadata(wifi_lan_usable_channels = WifiLanUsableChannels())
        )
      )
    ).send(writeChannel)
  }

  private suspend fun createConnection(readChannel: ByteReadChannel, writeChannel: ByteWriteChannel): D2DConnectionContext {
    val client = Ukey2Handshake.forInitiator(Ukey2Handshake.HandshakeCipher.P256_SHA512)

    log("Ukey2Handshake", "Message 1 (Client Init)")
    // Message 1 (Client Init)
    var handshakeMessage = client.getNextHandshakeMessage()
    writeChannel.writeFullyNearby(handshakeMessage)

    log("Ukey2Handshake", "Message 2 (Server Init)")
    // Message 2 (Server Init)
    handshakeMessage = readChannel.readByteArray()
    client.parseHandshakeMessage(handshakeMessage)

    log("Ukey2Handshake", "Message 3 (Client Finish)")
    // Message 3 (Client Finish)
    handshakeMessage = client.getNextHandshakeMessage()
    writeChannel.writeFullyNearby(handshakeMessage)

    log("Ukey2Handshake", "getVerificationString")
    // Get the auth string (used for PIN verification)
    client.getVerificationString(32)

    log("Ukey2Handshake", "verifyHandshake")
    // accept the handshake
    client.verifyHandshake()

    log("Ukey2Handshake", "send CONNECTION_RESPONSE")
    //send connection response
    // V1Frame{type=CONNECTION_RESPONSE, connection_response=ConnectionResponseFrame{status=0, response=ACCEPT, os_info=OsInfo{type=ANDROID}, multiplex_socket_bitmask=0}}
    OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.CONNECTION_RESPONSE,
        connection_response = ConnectionResponseFrame(
          status = 0,
          response = ConnectionResponseFrame.ResponseStatus.ACCEPT,
          os_info = OsInfo(CommonPlatformDependencies.osType().toOsInfo()),
          multiplex_socket_bitmask = 0
        )
      )
    ).send(writeChannel)

    return client.toConnectionContext()
  }

  private suspend fun makeIntroduction(
    readChannel: ByteReadChannel
  ) {

    // read connection response
    val connectionResponseFrame = readChannel.readByteArray().let { OfflineFrame.ADAPTER.decode(it) }
    require(connectionResponseFrame.v1?.type == V1Frame.FrameType.CONNECTION_RESPONSE) { "Invalid frame type. Expected CONNECTION_RESPONSE" }
    require(connectionResponseFrame.v1?.connection_response?.response == ConnectionResponseFrame.ResponseStatus.ACCEPT) { "Connection rejected" }

    // from now on we are encrypted

  }


}
