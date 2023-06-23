package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import com.google.location.nearby.connections.proto.*
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PacketType.DATA
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader.PayloadType.BYTES
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import d2d.D2DConnectionContext
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import okio.*
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

private const val SANE_FRAME_LENGTH = 524424

internal fun createEndpointInfo(currentDevice: CurrentDevice): ByteArray {
  val deviceName = currentDevice.deviceName

  return byteArrayOf(
    (deviceTypeId(currentDevice) shl 1).toByte(), // 0000 ddd0 (d == devicetype)
    *Random.nextBytes(16), // 16 bytes random
    deviceName.length.toByte(),
    *deviceName.encodeToByteArray()
  )
}

internal suspend fun Message<*, *>.sendEncryptedNearby(writeChannel: ByteWriteChannel, connection: D2DConnectionContext) {
  val encryptedMessage = connection.encodeMessageToPeer(encode())
  writeChannel.writeFullyNearby(encryptedMessage)
}

internal suspend fun Message<*, *>.send(writeChannel: ByteWriteChannel) {
  val message = encode()
  writeChannel.writeFullyNearby(message)
}

internal suspend fun ByteWriteChannel.writeFullyNearby(message: ByteArray) {
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

internal suspend fun D2DConnectionContext.receiveEncryptedOfflineMessage(
  readChannel: ByteReadChannel,
  writeChannel: ByteWriteChannel
): OfflineFrame {
  // todo recursive read buffer for byes payload
  val offlineFrame = decodeMessageFromPeer(readChannel.readByteArray()).let { OfflineFrame.ADAPTER.decode(it) }

  // if message received here was a keep alive, reply and read again
  if (offlineFrame.v1?.type == V1Frame.FrameType.KEEP_ALIVE) {
    log("NearbyReceiverConnectionHandler", "Received keep alive. Replying")
    replyKeepAlive(writeChannel, this)

    return receiveEncryptedOfflineMessage(readChannel, writeChannel)
  }

  if (offlineFrame.v1?.type == V1Frame.FrameType.DISCONNECTION) {
    log("NearbyReceiverConnectionHandler", "Received disconnection. Replying")

    throw IllegalStateException("Client sent disconnection message")
  }

  val header = offlineFrame.v1?.payload_transfer?.payload_header
  require(header != null) { "Payload header not found: $offlineFrame" }

  return if (header.type != BYTES) {
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

internal suspend fun ByteReadChannel.readNearbyFully(sink: Sink) {

  val channel = this
  sink.buffer().use { buffer ->

    if (channel.isClosedForRead) {
      throw IllegalStateException("Channel is closed")
    }

    val sizeBytes = ByteArray(4)
    val sizeByte = channel.readFully(sizeBytes).let { sizeBytes.map { it.toUInt() and 255u } }
    val size = ((sizeByte[0] shl 24) or (sizeByte[1] shl 16) or (sizeByte[2] shl 8) or sizeByte[3])

    val packet = channel.readRemaining(size.toLong())
    buffer.write(packet.readBytes())
  }
}

internal suspend fun ByteReadChannel.readByteArray(): ByteArray {
  val buffer = Buffer()
  readNearbyFully(buffer)
  val byteArray = buffer.readByteArray()
  log("readByteArray", "read ${byteArray.size}")
  return byteArray
}

/** from client */
internal suspend fun replyKeepAlive(writeChannel: ByteWriteChannel, nearbyConnection: D2DConnectionContext) {
  log("NearbyReceiverConnectionHandler", "Replying keep alive")

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

internal suspend fun sendKeepAlive(writeChannel: ByteWriteChannel, nearbyConnection: D2DConnectionContext) {
  log("NearbyReceiverConnectionHandler", "Sending keep alive")

  OfflineFrame(
    version = OfflineFrame.Version.V1,
    v1 = V1Frame(
      type = V1Frame.FrameType.KEEP_ALIVE,
      keep_alive = KeepAliveFrame(
        ack = false
      )
    )
  ).sendEncryptedNearby(writeChannel, nearbyConnection)
}

private fun deviceTypeId(currentDevice: CurrentDevice): Int {
  return when (currentDevice.deviceType) {
    DeviceType.MOBILE -> 1
    DeviceType.TABLET -> 2
    DeviceType.DESKTOP -> 3
//      else -> 0
  }
}

internal fun OsType.toOsInfo(): OsInfo.OsType {
  return when (this) {
    OsType.ANDROID -> OsInfo.OsType.ANDROID
    OsType.APPLE -> OsInfo.OsType.APPLE
    OsType.LINUX -> OsInfo.OsType.LINUX
    OsType.WINDOWS -> OsInfo.OsType.WINDOWS
    OsType.UNKNOWN -> OsInfo.OsType.UNKNOWN_OS_TYPE
  }
}

internal suspend fun sendEncryptedWrappedPayload(
  frame: Message<*, *>,
  writeChannel: ByteWriteChannel,
  nearbyConnection: D2DConnectionContext,
  payloadType: PayloadHeader.PayloadType = BYTES,
  payloadId: Long = Random.nextLong()
) {
  sendEncryptedWrappedPayload(frame.encodeByteString(), writeChannel, nearbyConnection, payloadType, payloadId)
}

internal suspend fun sendEncryptedWrappedPayload(
  totalBytePayload: ByteString,
  writeChannel: ByteWriteChannel,
  nearbyConnection: D2DConnectionContext,
  payloadType: PayloadHeader.PayloadType = BYTES,
  payloadId: Long = Random.nextLong()
) {

  val totalSize = totalBytePayload.size
  val totalSizeLong = totalSize.toLong()
  val parts = ceil(totalSize.toFloat() / SANE_FRAME_LENGTH).roundToInt()

  (0 until parts).forEach { chunkIndex ->

    val sizeStartRange = (chunkIndex * SANE_FRAME_LENGTH)
    val sizeEndRange = min(totalSize, (chunkIndex + 1) * SANE_FRAME_LENGTH)

    val chunkBody = totalBytePayload.substring(sizeStartRange, sizeEndRange)
    println("Sending chunk $chunkIndex range $sizeStartRange - $sizeEndRange. chunkBody ${chunkBody.size} total size $totalSize")

    sendChunkWrappedPayload(
      totalSize = totalSizeLong,
      payloadId = payloadId,
      offset = sizeStartRange.toLong(),
      bodyChunk = totalBytePayload.substring(sizeStartRange, sizeEndRange),
      payloadType = payloadType,
      writeChannel = writeChannel,
      nearbyConnection = nearbyConnection
    )
  }

  // send last chuck
  sendChunkWrappedPayload(
    totalSize = totalSizeLong,
    payloadId = payloadId,
    offset = totalSizeLong,
    bodyChunk = null,
    payloadType = payloadType,
    writeChannel = writeChannel,
    nearbyConnection = nearbyConnection
  )

}

internal suspend fun sendEncryptedWrappedPayload(
  bufferedSource: BufferedSource,
  totalSize: Long,
  writeChannel: ByteWriteChannel,
  nearbyConnection: D2DConnectionContext,
  payloadType: PayloadHeader.PayloadType = BYTES,
  payloadId: Long = Random.nextLong()
) {

  val parts = ceil(totalSize.toFloat() / SANE_FRAME_LENGTH).roundToInt()
  val readBuffer = Buffer()

  (0 until parts).forEach { chunkIndex ->

    val sizeStartRange = chunkIndex * SANE_FRAME_LENGTH
    val sizeEndRange = min(totalSize.toInt(), (chunkIndex + 1) * SANE_FRAME_LENGTH)

    bufferedSource.fillBuffer(readBuffer, (sizeEndRange - sizeStartRange).toLong())

    val chunkBody = readBuffer.readByteString()
    println("Sending chunk $chunkIndex range $sizeStartRange - $sizeEndRange. chunkBody ${chunkBody.size} total size $totalSize")

    sendChunkWrappedPayload(
      totalSize = totalSize,
      payloadId = payloadId,
      offset = sizeStartRange.toLong(),
      bodyChunk = chunkBody,
      payloadType = payloadType,
      writeChannel = writeChannel,
      nearbyConnection = nearbyConnection
    )
  }

  // send last chuck
  sendChunkWrappedPayload(
    totalSize = totalSize,
    payloadId = payloadId,
    offset = totalSize,
    bodyChunk = null,
    payloadType = payloadType,
    writeChannel = writeChannel,
    nearbyConnection = nearbyConnection
  )

}

private fun BufferedSource.fillBuffer(buffer: Buffer, size: Long) {

  do {
    val read = read(buffer, size)
  } while (read != -1L && buffer.size < size)

}


private suspend fun sendChunkWrappedPayload(
  totalSize: Long,
  payloadId: Long,
  offset: Long,
  bodyChunk: ByteString?,
  payloadType: PayloadHeader.PayloadType,
  writeChannel: ByteWriteChannel,
  nearbyConnection: D2DConnectionContext
){

  val payload = PayloadTransferFrame.PayloadChunk(
    offset = offset,
    flags = if (bodyChunk == null) 1 else 0,
    body = bodyChunk
  )

  val transferFrame = PayloadTransferFrame(
    packet_type = DATA,
    payload_chunk = payload,
    payload_header = PayloadHeader(
      id = payloadId,
      type = payloadType,
      total_size = totalSize,
      is_sensitive = false
    )
  )

  val wrapper = OfflineFrame(
    version = OfflineFrame.Version.V1,
    v1 = V1Frame(
      type = V1Frame.FrameType.PAYLOAD_TRANSFER,
      payload_transfer = transferFrame,
    )
  )

  wrapper.sendEncryptedNearby(writeChannel, nearbyConnection)
}

internal fun <M : Message<*, *>> extractPayloadFromOfflineFrame(offlineFrame: OfflineFrame, adapter: ProtoAdapter<M>): M {
  val body = offlineFrame.v1?.payload_transfer?.payload_chunk?.body
  require(body != null) { "Payload body not found" }

  return adapter.decode(body)
}