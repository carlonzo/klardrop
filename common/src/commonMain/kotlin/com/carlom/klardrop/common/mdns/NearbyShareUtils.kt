package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import com.google.location.nearby.connections.proto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.OsInfo
import com.google.location.nearby.connections.proto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PacketType.DATA
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader.PayloadType.BYTES
import com.google.location.nearby.connections.proto.V1Frame
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Sink
import okio.buffer
import okio.use
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

private const val SANE_FRAME_LENGTH = 524424
private const val LOG_DEBUG = true

private fun log(tag: String, message: String) {
  if (LOG_DEBUG)
    com.carlom.klardrop.common.utils.log(tag, message)
}

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

  // if message received here was a keep alive, reply and read again if is not and ack
  if (offlineFrame.v1?.type == V1Frame.FrameType.KEEP_ALIVE) {
    log("NearbyReceiverConnectionHandler", "Received keep alive with ack: ${offlineFrame.v1.keep_alive?.ack}")

    if (offlineFrame.v1.keep_alive?.ack == false) {
      replyKeepAlive(writeChannel, this)
    }

    return receiveEncryptedOfflineMessage(readChannel, writeChannel)
  }

  if (offlineFrame.v1?.type == V1Frame.FrameType.DISCONNECTION) {
    log("NearbyReceiverConnectionHandler", "Received disconnection. Replying")

    throw IllegalStateException("Client sent disconnection message")
  }

  val header = offlineFrame.v1?.payload_transfer?.payload_header
  require(header != null) { "Payload header not found: $offlineFrame" }

  val msg = if (header.type != BYTES) {
    offlineFrame
  } else {
    val chunkBody = offlineFrame.v1.payload_transfer.payload_chunk?.body
    require(chunkBody != null) { "Payload chunk not found" }
    recursiveReadOfflineFrame(readChannel, offlineFrame, header.id, chunkBody.toByteArray())
  }

  log("NearbyReceiverConnectionHandler", "receiveEncryptedOfflineMessage: Received message: $msg")

  return msg
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
    require(header.id == payloadId) { "Payload id mismatch header.id = ${header.id} payloadId = ${payloadId}. frame: $newOfflineFrame" }

    val newBody = if (newChunk.body == null) {
      byteArrayOf()
    } else {
      newChunk.body.toByteArray()
    }

    return recursiveReadOfflineFrame(readChannel, newOfflineFrame, payloadId, buffer + newBody)
  }

}

private suspend fun ByteReadChannel.readNearbyFully(sink: Sink) {

  val channel = this
  sink.buffer().use { buffer ->

    if (channel.isClosedForRead) {
      throw IllegalStateException("Channel is closed")
    }

    val sizeBytes = ByteArray(4)
    val sizeByte = channel.readFully(sizeBytes).let { sizeBytes.map { it.toUByte().toInt() } }
    val size = ((sizeByte[0] shl 24) or (sizeByte[1] shl 16) or (sizeByte[2] shl 8) or sizeByte[3])

    val packet = channel.readRemaining(size.toLong())
    buffer.write(packet.readBytes())
  }
}

internal suspend fun ByteReadChannel.readByteArray(): ByteArray {
  val buffer = Buffer()
  readNearbyFully(buffer)
  return buffer.readByteArray()
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

/** from server */
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
  //    https://github.com/google/nearby/blob/0d83625766a0be92e713d592a3c8bcc7fd6d3307/internal/proto/metadata.proto#L69
  // Device types: unknown=0, phone=1, tablet=2, laptop=3,4

  return when (currentDevice.deviceType) {
    DeviceType.MOBILE -> 1
    DeviceType.DESKTOP -> 3
    DeviceType.UNKNOWN -> 0
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
  sendEncryptedWrappedPayload(frame.encode(), writeChannel, nearbyConnection, payloadType, payloadId)
}

internal suspend fun sendEncryptedWrappedPayload(
  payload: ByteArray,
  writeChannel: ByteWriteChannel,
  nearbyConnection: D2DConnectionContext,
  payloadType: PayloadHeader.PayloadType = BYTES,
  payloadId: Long = Random.nextLong()
) {

  val totalSize = payload.size
  val totalSizeLong = totalSize.toLong()
  val parts = ceil(totalSize.toFloat() / SANE_FRAME_LENGTH).roundToInt()

  (0 until parts).forEach { chunkIndex ->

    val sizeStartRange = (chunkIndex * SANE_FRAME_LENGTH)
    val size = min(SANE_FRAME_LENGTH, totalSize - sizeStartRange)
    val chunk = payload.toByteString(sizeStartRange, size)
    println("Sending chunk $chunkIndex chunkBody from:sizeStartRange size: ${size} chunkSize: ${chunk.size} total size $totalSize")

    sendChunkWrappedPayload(
      totalSize = totalSizeLong,
      payloadId = payloadId,
      offset = sizeStartRange.toLong(),
      bodyChunk = chunk,
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

/**
 * Send a chunk of data wrapped in a [OfflineFrame] to the [writeChannel]
 *
 * returns a Flow with percentage progress
 */
internal suspend fun sendEncryptedWrappedPayload(
  bufferedSource: BufferedSource,
  totalSize: Long,
  writeChannel: ByteWriteChannel,
  nearbyConnection: D2DConnectionContext,
  payloadType: PayloadHeader.PayloadType = BYTES,
  payloadId: Long = Random.nextLong()
): Flow<Int> = flow {

  emit(0)

  val parts = ceil(totalSize.toFloat() / SANE_FRAME_LENGTH).roundToInt()
  val readBuffer = Buffer()

  var sentOffset = 0L
  (0 until parts).forEach { chunkIndex ->

    val sizeStartRange = (chunkIndex * SANE_FRAME_LENGTH)
    val size = min(SANE_FRAME_LENGTH, totalSize.toInt() - sizeStartRange)

    bufferedSource.fillBuffer(readBuffer, size.toLong())

    val chunkBody = readBuffer.readByteString()
    println("Sending chunk $chunkIndex range $sizeStartRange chunkBody ${chunkBody.size} total size $totalSize")

    sendChunkWrappedPayload(
      totalSize = totalSize,
      payloadId = payloadId,
      offset = sentOffset,
      bodyChunk = chunkBody,
      payloadType = payloadType,
      writeChannel = writeChannel,
      nearbyConnection = nearbyConnection
    )

    sentOffset += chunkBody.size

    emit((sentOffset / totalSize * 100L).toInt().coerceIn(0, 100))
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

  emit(100)
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
) {

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