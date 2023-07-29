package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.getMimeTypeFromExtension
import com.carlom.klardrop.common.utils.log
import com.carlonzo.ukey2.Ukey2Handshake
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import com.google.location.nearby.connections.proto.*
import com.google.security.cryptauth.lib.securegcm.DeviceType
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import okio.ByteString.Companion.toByteString
import sharing.nearby.Frame
import sharing.nearby.PairedKeyEncryptionFrame
import sharing.nearby.PairedKeyResultFrame
import kotlin.random.Random

/**
 *  This class handles the connection between two devices.
 */
class NearbyReceiverConnectionHandler(
  private val internalPlatformDependencies: InternalPlatformDependencies,
  private val fileManager: FileManager,
  coroutines: Coroutines,
) {

  private val filesToTransfer = mutableMapOf<Long, FileTransfer>()
  private val connectionScope = CoroutineScope(coroutines.ioDispatcher)

  private var keepAliveWhileWaitingJob: Job? = null

  suspend fun onConnection(connection: Socket) {

    try {
      val readChannel = connection.openReadChannel()
      val writeChannel = connection.openWriteChannel(autoFlush = true)

      // connection request
      val connectionRequest = readChannel.readByteArray().let { OfflineFrame.ADAPTER.decode(it) }

      val msg = connectionRequest.toString()
      log("NearbyReceiverConnectionHandler", "Connection request received ${msg.substring(0, msg.length / 2)}")
      log("NearbyReceiverConnectionHandler", "Connection request received ${msg.substring(msg.length / 2)}")
      processConnectionRequest(connectionRequest)

      val nearbyConnection = createConnection(readChannel, writeChannel)
      log("NearbyReceiverConnectionHandler", "Handshake completed $nearbyConnection")

      makeIntroduction(writeChannel)

      handleKeyPairExchange(readChannel, writeChannel, nearbyConnection)

      handleTransferSetup(readChannel, writeChannel, nearbyConnection)

      // create a job to keep alive while waiting for user to accept
      keepAliveWhileWaitingJob = connectionScope.launch {

        while (isActive) {
          nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
        }

      }

      // simulate user to accept the transfer
      delay(500)

      // just accept directly
      keepAliveWhileWaitingJob?.cancel()
      acceptTransfer(nearbyConnection, writeChannel)

      receiveTransfer(readChannel, writeChannel, nearbyConnection)

    } finally {
      connection.close()
    }

  }

  private suspend fun receiveTransfer(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {

    log("NearbyReceiverConnectionHandler", "Start receiving transfer")
    while (true) {
      // receive and wait until we get a filechunk

      val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
      if (offlineFrame.v1?.type == V1Frame.FrameType.PAYLOAD_TRANSFER) {

        log("NearbyReceiverConnectionHandler", "Payload transfer received ${offlineFrame.v1}")

        val payload = offlineFrame.v1.payload_transfer
        require(payload != null) { "Payload not found" }

        val header = payload.payload_header
        require(header != null) { "Payload header not found" }

        if (header.type == PayloadTransferFrame.PayloadHeader.PayloadType.FILE) {

          val payloadChunk = payload.payload_chunk
          require(payloadChunk != null) { "Payload chunk body not found" }


          if (payloadChunk.body == null || payloadChunk.body.size == 0) {
            val fileTransfer = filesToTransfer[header.id]!!
            fileTransfer.onTransferCompleted()
            log("NearbyReceiverConnectionHandler", "File transfer completed")
            break
          }

          processFileChunk(payload)
        }
      }

    }


  }

  private fun processFileChunk(payloadTransfer: PayloadTransferFrame) {
    val fileTransfer = filesToTransfer[payloadTransfer.payload_header!!.id]
    require(fileTransfer != null) { "File transfer not found" }

    fileTransfer.bufferedSink.write(payloadTransfer.payload_chunk!!.body!!)
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
            type = internalPlatformDependencies.osType().toOsInfo()
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

    // useless read
    nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
      .let { offline ->
        val paylod = offline.v1?.payload_transfer?.payload_chunk!!
        log("NearbyReceiverConnectionHandler", "Received key result: ${Frame.ADAPTER.decode(paylod.body!!)}}")
      }


    log("NearbyReceiverConnectionHandler", "Sending paired encryption completed")

  }

  private fun processIntroductionFrame(
    frame: Frame
  ) {

    frame.v1?.introduction?.file_metadata?.forEach { fileMetadata ->

      val fileTransfer = fileManager.prepareSaveFile(
        fileName = fileMetadata.name!!,
        mimeType = fileMetadata.mime_type ?: getMimeTypeFromExtension(fileMetadata.name)
      )

      filesToTransfer[fileMetadata.payload_id!!] = fileTransfer
      log("NearbyReceiverConnectionHandler", "File transfer prepared: $fileMetadata")
    }

  }

  private suspend fun receiveTransferSetupFrame(
    nearbyConnection: D2DConnectionContext,
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel
  ): Frame {
    val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel, writeChannel)
    println("receiveTransferSetupFrame $offlineFrame")

    val header = offlineFrame.v1?.payload_transfer?.payload_header
    require(header != null) { "Payload header not found" }
    require(header.type == PayloadTransferFrame.PayloadHeader.PayloadType.BYTES) { "Payload type is not bytes" }

    val bodyPayload = offlineFrame.v1.payload_transfer.payload_chunk?.body
    require(bodyPayload != null) { "Payload body not found" }
    return Frame.ADAPTER.decode(bodyPayload.toByteArray())
  }

}
