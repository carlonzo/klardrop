package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import com.google.location.nearby.connections.proto.*
import com.google.security.cryptauth.lib.securegcm.DeviceType
import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import com.squareup.wire.Message
import d2d.D2DConnectionContext
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Sink
import okio.buffer
import okio.use
import sharing.nearby.Frame
import sharing.nearby.PairedKeyEncryptionFrame
import sharing.nearby.PairedKeyResultFrame
import kotlin.random.Random

class NearbyConnectionHandler(
  private val internalPlatformDependencies: InternalPlatformDependencies,
  private val fileManager: FileManager,
  private val coroutines: Coroutines,
) {

  private var encryptionDone = false
  private val filesToTransfer = mutableMapOf<Long, FileTransfer>()
  private val connectionScope = CoroutineScope(coroutines.ioDispatcher)

  private var keepAliveWhileWaitingJob: Job? = null

  suspend fun onConnection(connection: Socket) {
    try {
      val readChannel = connection.openReadChannel()
      val writeChannel = connection.openWriteChannel(autoFlush = true)

      // connection request
      val connectionRequest = readChannel.readByteArray().let { OfflineFrame.ADAPTER.decode(it) }
      processConnectionRequest(connectionRequest)

      val nearbyConnection = createConnection(readChannel, writeChannel)
      log("NearbyConnectionHandler", "Handshake completed $nearbyConnection")

      makeIntroduction(nearbyConnection, readChannel, writeChannel)

      handleTransferSetup(readChannel, writeChannel, nearbyConnection)

      // simulate user to accept the transfer
      delay(500)

      // just accept directly
      keepAliveWhileWaitingJob?.cancel()
      acceptTransfer(nearbyConnection, writeChannel)

      receiveTransfer(readChannel, nearbyConnection)

    } finally {
      connection.close()
    }

  }

  private suspend fun receiveTransfer(
    readChannel: ByteReadChannel,
    nearbyConnection: D2DConnectionContext
  ) {

    while (true) {
      // receive and wait until we get a filechunk

      val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel)
      if (offlineFrame.v1?.type == V1Frame.FrameType.PAYLOAD_TRANSFER) {

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
            log("NearbyConnectionHandler", "File transfer completed")
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
    log("NearbyConnectionHandler", "acceptTransfer")
    val frame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.RESPONSE,
        connection_response = sharing.nearby.ConnectionResponseFrame(
          status = sharing.nearby.ConnectionResponseFrame.Status.ACCEPT
        )
      )
    )

    sendTransferSetupFrame(frame, writeChannel, nearbyConnection)
  }

  private suspend fun handleTransferSetup(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {
    // method: processIntroductionFrame
    val introductionFrame = receiveTransferSetupFrame(nearbyConnection, readChannel)

    processIntroductionFrame(introductionFrame, writeChannel, readChannel, nearbyConnection)

    log("NearbyConnectionHandler", "handleTransferSetup completed")
  }

  private fun processConnectionRequest(connectionRequest: OfflineFrame) {
    val endpointInfo =
      connectionRequest.v1?.connection_request?.endpoint_info?.toByteArray() ?: throw IllegalStateException("Endpoint info not found")
    require(endpointInfo.size > 17) { "Endpoint info is too short" }

    val deviceNameLength = endpointInfo[17].toInt()
    val deviceName = endpointInfo.copyOfRange(18, 18 + deviceNameLength).decodeToString()
    val deviceType = DeviceType.fromValue((endpointInfo[0].toInt() and 7) shr 1)

    log("NearbyConnectionHandler", "Connection request from $deviceName ($deviceType)")
  }


  private suspend fun createConnection(readChannel: ByteReadChannel, writeChannel: ByteWriteChannel): D2DConnectionContext {
    val server = Ukey2Handshake.forResponder(Ukey2Handshake.HandshakeCipher.P256_SHA512)

    // Message 1 (Client Init)
    val handshakeMessage = readChannel.readByteArray()
    server.parseHandshakeMessage(handshakeMessage)
    log("NearbyConnectionHandler", "Client Init received")

    // Message 2 (Server Init)
    val nextHandshakeMessage = server.nextHandshakeMessage
    writeChannel.writeFullyNearby(nextHandshakeMessage)
    log("NearbyConnectionHandler", "Server Init sent")

    // Message 3 (Client Finish)
    val clientHandshake = readChannel.readByteArray()
    server.parseHandshakeMessage(clientHandshake)
    log("NearbyConnectionHandler", "Client Finish received")

    // Get the auth string
    val verificationString = server.getVerificationString(32)

    // lets accept everything
    server.verifyHandshake()


    val clientKey = readChannel.readByteArray()
    OfflineFrame.ADAPTER.decode(clientKey)



    log("NearbyConnectionHandler", "Handshake completed")
    return server.toConnectionContext()
  }

  private suspend fun makeIntroduction(
    nearbyConnection: D2DConnectionContext, readChannel: ByteReadChannel, writeChannel: ByteWriteChannel
  ) {

    // accept request
    log("NearbyConnectionHandler", "Accepting connection")
    OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.CONNECTION_RESPONSE,
        connection_response = ConnectionResponseFrame(
          response = ConnectionResponseFrame.ResponseStatus.ACCEPT,
          status = 0,
          os_info = OsInfo(
            type = osInfo()
          )
        )
      )
    ).send(writeChannel)

    encryptionDone = true

    // send paired encryption
    // processConnectionResponseFrame
    log("NearbyConnectionHandler", "Sending paired encryption")

    val pairedKeyEncryptionFrame = Frame(
      version = Frame.Version.V1,
      v1 = sharing.nearby.V1Frame(
        type = sharing.nearby.V1Frame.FrameType.PAIRED_KEY_ENCRYPTION,
        paired_key_encryption = PairedKeyEncryptionFrame(
          secret_id_hash = Random.nextBytes(6).toByteString(),
          signed_data = Random.nextBytes(72).toByteString(),
        )
      )
    )
    sendTransferSetupFrame(pairedKeyEncryptionFrame, writeChannel, nearbyConnection)

//    sentConnectionResponse done
    log("NearbyConnectionHandler", "Sent connection response")

    handleKeyPairExchange(readChannel, writeChannel, nearbyConnection)


    log("NearbyConnectionHandler", "Sending paired encryption completed")

  }

  private suspend fun handleKeyPairExchange(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {
    val keyPairSetupFrame = receiveTransferSetupFrame(nearbyConnection, readChannel)

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

    sendTransferSetupFrame(pairedResult, writeChannel, nearbyConnection)
    // state: sentPairedKeyResult


    // methods: processPairedKeyResultFrame
    // -- does nothing
    // state: receivedPairedKeyResult

    // useless read
    nearbyConnection.receiveEncryptedOfflineMessage(readChannel)
      .let { log("NearbyConnectionHandler", "Maybe useless read: $it") }


    log("NearbyConnectionHandler", "handleKeyPairExchange Received")
  }

  private suspend fun processIntroductionFrame(
    frame: Frame,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    nearbyConnection: D2DConnectionContext
  ) {

    frame.v1?.introduction?.file_metadata?.forEach { fileMetadata ->

      val fileTransfer = fileManager.prepareSaveFile(
        fileName = fileMetadata.name!!,
        mimeType = fileMetadata.mime_type
      )

      filesToTransfer[fileMetadata.payload_id!!] = fileTransfer
      log("NearbyConnectionHandler", "File transfer prepared: $fileMetadata -> $fileTransfer")
    }

    // create a job to keep alive while waiting for user to accept
    keepAliveWhileWaitingJob = connectionScope.launch {

      while (isActive) {
        receiveKeepAlive(readChannel, writeChannel, nearbyConnection)
      }

    }

  }

  private suspend fun receiveKeepAlive(
    readChannel: ByteReadChannel,
    writeChannel: ByteWriteChannel,
    nearbyConnection: D2DConnectionContext
  ) {
    val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel)

    if (offlineFrame.v1?.type == V1Frame.FrameType.KEEP_ALIVE) {
      log("NearbyConnectionHandler", "Received keep alive")
      sendKeepAlive(writeChannel, nearbyConnection)
    } else {
      log("NearbyConnectionHandler", "Waiting for keep alive but received unknown frame: $offlineFrame")
    }

  }

  private suspend fun sendKeepAlive(writeChannel: ByteWriteChannel, nearbyConnection: D2DConnectionContext) {
    log("NearbyConnectionHandler", "Sending keep alive")

    OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.KEEP_ALIVE,
        keep_alive = KeepAliveFrame(
          ack = true
        )
      )
    ).sendEncryptedNearby(writeChannel, nearbyConnection)
  }

  private suspend fun receiveTransferSetupFrame(
    nearbyConnection: D2DConnectionContext,
    readChannel: ByteReadChannel,
  ): Frame {
    val offlineFrame = nearbyConnection.receiveEncryptedOfflineMessage(readChannel)
    println("receiveTransferSetupFrame $offlineFrame")

    val header = offlineFrame.v1?.payload_transfer?.payload_header
    require(header != null) { "Payload header not found" }
    require(header.type == PayloadTransferFrame.PayloadHeader.PayloadType.BYTES) { "Payload type is not bytes" }

    val bodyPaylod = offlineFrame.v1.payload_transfer.payload_chunk?.body
    require(bodyPaylod != null) { "Payload body not found" }
    return Frame.ADAPTER.decode(bodyPaylod.toByteArray())
  }


  private suspend fun sendTransferSetupFrame(frame: Frame, writeChannel: ByteWriteChannel, nearbyConnection: D2DConnectionContext) {
    val payload = PayloadTransferFrame.PayloadChunk(
      offset = 0,
      flags = 0,
      body = frame.encodeByteString()
    )

    val transferFrame = PayloadTransferFrame(
      packet_type = PayloadTransferFrame.PacketType.DATA,
      payload_chunk = payload,
      payload_header = PayloadTransferFrame.PayloadHeader(
        id = Random.nextLong(),
        type = PayloadTransferFrame.PayloadHeader.PayloadType.BYTES,
        total_size = payload.body?.size?.toLong()!!,
        is_sensitive = false
      )
    )

    val wrapper = OfflineFrame(
      version = OfflineFrame.Version.V1,
      v1 = V1Frame(
        type = V1Frame.FrameType.PAYLOAD_TRANSFER,
        payload_transfer = transferFrame
      )
    )
    wrapper.sendEncryptedNearby(writeChannel, nearbyConnection)

    wrapper.copy(
      v1 = wrapper.v1?.copy(
        payload_transfer = transferFrame.copy(
          payload_chunk = transferFrame.payload_chunk!!.copy(
            flags = 1, // lastChunk
            offset = transferFrame.payload_chunk.body?.size?.toLong(),
            body = null
          )
        )
      )
    ).sendEncryptedNearby(writeChannel, nearbyConnection)


  }

  private suspend fun ByteReadChannel.readByteArray(): ByteArray {
    val buffer = Buffer()
    log("readByteArray", "started reading")
    readNearbyFully(buffer)
    val byteArray = buffer.readByteArray()
    log("readByteArray", "read ${byteArray.size}")
    return byteArray
  }

  private suspend fun readAndDecryptMessage(readChannel: ByteReadChannel, connection: D2DConnectionContext): ByteArray {
    val encryptedMessage = readChannel.readByteArray()
    return connection.decodeMessageFromPeer(encryptedMessage)
  }

  private fun osInfo(): OsInfo.OsType {
    return when (internalPlatformDependencies.osType()) {
      OsType.ANDROID -> OsInfo.OsType.ANDROID
      OsType.APPLE -> OsInfo.OsType.APPLE
      OsType.LINUX -> OsInfo.OsType.LINUX
      OsType.WINDOWS -> OsInfo.OsType.WINDOWS
      OsType.UNKNOWN -> OsInfo.OsType.UNKNOWN_OS_TYPE
    }
  }

  private suspend fun Message<*, *>.sendEncryptedNearby(writeChannel: ByteWriteChannel, connection: D2DConnectionContext) {
    if (!encryptionDone) {
      log("NearbyConnectionHandler", "write encrypted but encryption is NOTE done!! are you sure?")
    }

    val encryptedMessage = connection.encodeMessageToPeer(encode())
    writeChannel.writeFullyNearby(encryptedMessage)
  }

  private suspend fun Message<*, *>.send(writeChannel: ByteWriteChannel) {
    if (encryptionDone) {
      log("NearbyConnectionHandler", "write plain but encryption is done!! are you sure?")
    }
    val message = encode()
    writeChannel.writeFullyNearby(message)
  }

  private suspend fun ByteWriteChannel.writeFullyNearby(message: ByteArray) {
    log("writeFullyNearby", "Writing ${message.size + 4} bytes")
    val size = message.size
    val sizeByteArray = byteArrayOf(
      size.shr(24).toByte(),
      size.shr(16).toByte(),
      size.shr(8).toByte(),
      size.toByte()
    )

    writeAvailable(sizeByteArray)
    writeFully(message)
    flush()
  }

//  private suspend fun D2DConnectionContext.receiveEncryptedMessage(readChannel: ByteReadChannel): ByteArray {
//    val message = readChannel.readByteArray()
//    return decodeMessageFromPeer(message)
//  }

  private suspend fun D2DConnectionContext.receiveEncryptedOfflineMessage(readChannel: ByteReadChannel): OfflineFrame {
    // todo recursive read buffer for byes payload
    val offlineFrame = decodeMessageFromPeer(readChannel.readByteArray()).let { OfflineFrame.ADAPTER.decode(it) }

    val header = offlineFrame.v1?.payload_transfer?.payload_header
    require(header != null) { "Payload header not found" }

    return if (header.type != PayloadTransferFrame.PayloadHeader.PayloadType.BYTES) {
      offlineFrame
    } else {
      val chunkBody = offlineFrame.v1.payload_transfer.payload_chunk?.body
      require(chunkBody != null) { "Payload chunk not found" }
      recursiveReadOfflineFrame(readChannel, offlineFrame, header.id, chunkBody.toByteArray())
    }
  }

  private suspend fun D2DConnectionContext.recursiveReadOfflineFrame(
    readChannel: ByteReadChannel,
    offlineFrame: OfflineFrame,
    payloadId: Long?,
    buffer: ByteArray = byteArrayOf()
  ): OfflineFrame {

    val chunk = offlineFrame.v1?.payload_transfer?.payload_chunk
    require(chunk != null) { "Payload chunk not found" }

    if (chunk.flags!! and 1 == 1) {
      log("recursiveReadOfflineFrame", "last chunk found")

      return offlineFrame.copy(
        v1 = offlineFrame.v1.copy(
          payload_transfer = offlineFrame.v1.payload_transfer.copy(
            payload_chunk = chunk.copy(body = (buffer).toByteString())
          )
        )
      )
    } else {
      log("recursiveReadOfflineFrame", "reading next chunk")

      val newOfflineFrame = decodeMessageFromPeer(readChannel.readByteArray()).let { OfflineFrame.ADAPTER.decode(it) }
      val newChunk = newOfflineFrame.v1?.payload_transfer?.payload_chunk
      require(newChunk != null) { "Payload chunk not found" }

      val header = newOfflineFrame.v1.payload_transfer.payload_header
      require(header != null) { "Payload header not found" }
      require(header.id == payloadId) { "Payload id mismatch" }

      val newBody = if (newChunk.body == null) {
        byteArrayOf()
      } else {
        newChunk.body.toByteArray()
      }

      return recursiveReadOfflineFrame(readChannel, newOfflineFrame, payloadId, buffer + newBody)
    }

  }
}


suspend fun ByteReadChannel.readNearbyFully(sink: Sink) {

  val channel = this
  sink.buffer().use { buffer ->

    if (channel.isClosedForRead) {
      throw IllegalStateException("Channel is closed")
    }

    val sizeBytes = ByteArray(4)
    val sizeByte = channel.readFully(sizeBytes).let { sizeBytes.map { it.toUInt() and 255u } }
    val size = ((sizeByte[0] shl 24) or (sizeByte[1] shl 16) or (sizeByte[2] shl 8) or sizeByte[3])

    log("readFully", "Reading $size bytes: $sizeByte")

    val packet = channel.readRemaining(size.toLong())
    buffer.write(packet.readBytes())

    log("readFully", "Reading completed")
  }
}
