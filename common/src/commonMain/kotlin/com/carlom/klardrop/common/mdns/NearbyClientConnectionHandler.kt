package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.toByteArray
import com.carlonzo.ukey2.Ukey2Handshake
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import com.google.location.nearby.connections.proto.*
import com.google.location.nearby.connections.proto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.V1Frame
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.delay
import okio.ByteString.Companion.toByteString
import okio.use
import sharing.nearby.*
import sharing.nearby.ConnectionResponseFrame.Status
import sharing.nearby.PairedKeyEncryptionFrame
import sharing.nearby.V1Frame.FrameType
import kotlin.random.Random

class NearbyClientConnectionHandler(
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val internalPlatformDependencies: InternalPlatformDependencies,
  private val fileManager: FileManager,
  private val sendRequests: List<SendMessageRequest>,
) {

  // transfer requests id -> SendMessageRequest
  private val transfers = sendRequests.associateBy { Random.nextLong() }

  suspend fun onConnection(connection: Socket) {

    log("NearbyClientConnectionHandler", "Starting connection")

    try {
      val readChannel = connection.openReadChannel()
      val writeChannel = connection.openWriteChannel(autoFlush = true)

      sendConnectionRequest(writeChannel)
      val nearbyConnection = createConnection(readChannel, writeChannel)

      makeIntroduction(readChannel)
      handleKeyPairExchange(readChannel, writeChannel, nearbyConnection)

      handleTransferSetup(writeChannel, nearbyConnection)

      waitForTransferResponse(readChannel, writeChannel, nearbyConnection)

      initiateTransfer(readChannel, writeChannel, nearbyConnection)

    } finally {
      connection.close()
    }

  }

  private suspend fun initiateTransfer(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {
    log("initiateTransfer", "Initiating transfer")

    // unknown first payload
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
          log("initiateTransfer", "Trasnfering text message: ${textMessage}")

          sendEncryptedWrappedPayload(
            payload = textMessage.text.toByteArray(),
            payloadType = PayloadTransferFrame.PayloadHeader.PayloadType.BYTES,
            payloadId = id,
            writeChannel = writeChannel,
            nearbyConnection = nearbyConnection
          )
        }

        is FileMessage.SendRequest -> {
          log("initiateTransfer", "Trasnfering file: ${request.message}")

          fileManager.getReadStreamFromUri(request.pathFile).use { bufferedSource ->

            sendEncryptedWrappedPayload(
              bufferedSource = bufferedSource,
              totalSize = request.message.fileSize,
              payloadType = PayloadTransferFrame.PayloadHeader.PayloadType.FILE,
              payloadId = id,
              writeChannel = writeChannel,
              nearbyConnection = nearbyConnection
            )

          }


        }
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

        if (frame.v1.connection_response?.status == Status.ACCEPT) {
          // transfer accepted. we can start
          break
        } else {
          throw IllegalStateException("Connection refused $frame")
        }

      }

    }

  }

  private suspend fun handleTransferSetup(writeChannel: ByteWriteChannel, nearbyConnection: D2DConnectionContext) {

    val fileMetadatas = transfers.filterValues { it is FileMessage.SendRequest }.map { (id, request) ->

      request as FileMessage.SendRequest

      val mimetype = request.message.mimeType.lowercase()

      val fileType: FileMetadata.Type =
        if (mimetype.startsWith("image/")) FileMetadata.Type.IMAGE
        else if (mimetype.startsWith("video/")) FileMetadata.Type.VIDEO
        else if (mimetype.startsWith("audio/")) FileMetadata.Type.AUDIO
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
    // receive key pair encryption
    val serverKeyEncryptionFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    log("NearbyClientConnectionHandler", "received serverKeyEncryptionFrame: $serverKeyEncryptionFrame")

    val clientKeyEncryptionFrame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = FrameType.PAIRED_KEY_ENCRYPTION,
        paired_key_encryption = PairedKeyEncryptionFrame(
          secret_id_hash = Random.nextBytes(6).toByteString(),
          signed_data = Random.nextBytes(71).toByteString(),
        )
      )
    )

    sendEncryptedWrappedPayload(clientKeyEncryptionFrame, writeChannel, nearbyConnection)

    // receive exchange result

    val receivedExchangeResponse = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    log("NearbyClientConnectionHandler", "received receivedExchangeResponse: $receivedExchangeResponse")

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

    log("NearbyClientConnectionHandler", "Sending paired encryption completed: $clientExchangeResponse")
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

    log("// Message 1 (Client Init)")
    // Message 1 (Client Init)
    var handshakeMessage = client.getNextHandshakeMessage()
    writeChannel.writeFullyNearby(handshakeMessage)

    log("// Message 2 (Server Init)")
    // Message 2 (Server Init)
    handshakeMessage = readChannel.readByteArray()
    client.parseHandshakeMessage(handshakeMessage)

    log("// Message 3 (Client Finish)")
    // Message 3 (Client Finish)
    handshakeMessage = client.getNextHandshakeMessage()
    writeChannel.writeFullyNearby(handshakeMessage)

    log("getVerificationString")
    // Get the auth string
    val clientAuthString = client.getVerificationString(32)

    log("verifyHandshake")
    // accept the handshake
    client.verifyHandshake()

    log("send CONNECTION_RESPONSE")
    //send connection response
    // V1Frame{type=CONNECTION_RESPONSE, connection_response=ConnectionResponseFrame{status=0, response=ACCEPT, os_info=OsInfo{type=ANDROID}, multiplex_socket_bitmask=0}}
    OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.CONNECTION_RESPONSE,
        connection_response = ConnectionResponseFrame(
          status = 0,
          response = ConnectionResponseFrame.ResponseStatus.ACCEPT,
          os_info = OsInfo(internalPlatformDependencies.osType().toOsInfo()),
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
