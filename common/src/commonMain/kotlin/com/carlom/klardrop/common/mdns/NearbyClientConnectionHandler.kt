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

    log("NearbyClientConnectionHandler", "Starting Nearby Share transfer")
    sendFlow.emit(Pending)

    try {
      val readChannel = connection.openReadChannel()
      val writeChannel = connection.openWriteChannel(autoFlush = false)

      sendConnectionRequest(writeChannel)
      val nearbyConnection = createConnection(readChannel, writeChannel)
      makeIntroduction(readChannel)

      log("NearbyClientConnectionHandler", "Performing paired key exchange")
      handleKeyPairExchange(readChannel, writeChannel, nearbyConnection)

      handleTransferSetup(writeChannel, nearbyConnection)
      waitForTransferResponse(readChannel, writeChannel, nearbyConnection)

      log("NearbyClientConnectionHandler", "Starting file transfer")
      initiateTransfer(writeChannel, nearbyConnection, sendFlow)

    } catch (e: Exception) {
      log("NearbyClientConnectionHandler", "Transfer failed", e)
      sendFlow.emit(MessengerSendProgress.Error(e.message ?: "Unknown error"))
      throw e
    } finally {
      connection.close()
    }

    log("NearbyClientConnectionHandler", "Transfer completed successfully")
    sendFlow.emit(Completed)

  }

  private suspend fun initiateTransfer(
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext,
    sendFlow: MutableSharedFlow<MessengerSendProgress>
  ) {
    // Send initial protocol handshake payload
    sendEncryptedWrappedPayload(
      payload = listOf(8, 1, 18, 11, 8, 7, 58, 7, 13, 0, 0, 0, 0, 16, 1).toByteArray(),
      writeChannel = writeChannel,
      nearbyConnection = nearbyConnection
    )

    transfers.forEach {
      val id = it.key

      when (val request = it.value) {

        is SimpleSendMessageRequest -> {
          val textMessage = request.message as TextMessage
          sendEncryptedWrappedPayload(
            payload = textMessage.text.toByteArray(),
            payloadType = PayloadTransferFrame.PayloadHeader.PayloadType.BYTES,
            payloadId = id,
            writeChannel = writeChannel,
            nearbyConnection = nearbyConnection
          )
        }

        is FileMessage.FileSendRequest -> {
          log("NearbyClientConnectionHandler", "Sending file: ${request.message.fileName}")

          fileManager.getReadStreamFrom(request.file).buffered().use { source ->
            sendEncryptedWrappedPayload(
              source = source,
              totalSize = request.message.fileSize,
              payloadType = PayloadTransferFrame.PayloadHeader.PayloadType.FILE,
              payloadId = id,
              writeChannel = writeChannel,
              nearbyConnection = nearbyConnection
            ).collect { progress ->
              sendFlow.emit(InProgress(progress))
            }
          }
        }

        is SignedSendMessageRequest -> error("SignedSendMessageRequest is not supported in Nearby transfers ")
      }
    }

  }

  private suspend fun waitForTransferResponse(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {

    while (true) {

      sendKeepAlive(writeChannel, nearbyConnection)
      val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)

      if (offlineFrame.v1?.type == V1Frame.FrameType.KEEP_ALIVE) {
        delay(5000)
        continue
      }

      val frame = extractPayloadFromOfflineFrame(offlineFrame, Frame.ADAPTER)
      val frameType = frame.v1?.type ?: throw IllegalStateException("Frame type not found $offlineFrame $frame")

      if (frameType == FrameType.RESPONSE) {

        if (frame.v1?.connection_response?.status == Status.ACCEPT) {
          log("NearbyClientConnectionHandler", "Transfer accepted by receiver")
          break
        } else {
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

    sendEncryptedWrappedPayload(frame, writeChannel, nearbyConnection)
  }

  private suspend fun handleKeyPairExchange(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {
    // Step 1: Receive server's PairedKeyEncryptionFrame (SERVER SENDS FIRST per protocol diagram line 97)
    nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)

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

    // Step 3: Receive server's PairedKeyResultFrame (SERVER SENDS FIRST per protocol diagram line 99)
    nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)

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
