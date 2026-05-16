package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.common.utils.log
import com.carlonzo.ukey2.d2d.D2DConnectionContext
import com.google.location.nearby.connections.proto.DisconnectionFrame
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
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeByteArray
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.okio.toOkioByteString
import kotlinx.io.readByteString
import okio.ByteString.Companion.toByteString
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

private const val SANE_FRAME_LENGTH = 512 * 1024
private const val LOG_DEBUG = true

/**
 * Test-observability hook: bumped every time either side of the Nearby Share
 * conversation receives a DISCONNECTION frame from its peer. Used by the
 * integration test that pins the post-transfer DISCONNECTION exchange so we
 * don't regress it again. Not used by production code outside of bumping the
 * counter.
 */
object NearbyDisconnectionObserver {
  private var counter: Int = 0
  fun recordPeerDisconnection() { counter += 1 }
  fun observed(): Int = counter
  fun reset() { counter = 0 }
}

private fun log(tag: String, message: String) {
  if (LOG_DEBUG)
    log(tag, message)
}

internal fun createEndpointInfo(currentDevice: CurrentDevice): ByteArray {
  // The wire length is the UTF-8 byte count, not the UTF-16 code-unit count
  // returned by String.length — they differ for non-ASCII names.
  val nameBytes = currentDevice.deviceName.encodeToByteArray().let {
    if (it.size > 255) it.copyOfRange(0, 255) else it
  }

  return byteArrayOf(
    (deviceTypeId(currentDevice) shl 1).toByte(), // 0000 ddd0 (d == devicetype)
    *Random.nextBytes(16), // 16 bytes random
    nameBytes.size.toByte(),
    *nameBytes,
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

  writeByteArray(sizeByteArray)
  writeFully(message)
  flush()
}

internal suspend fun D2DConnectionContext.receiveEncryptedOfflineMessage(
  readChannel: ByteReadChannel,
  writeChannel: ByteWriteChannel
): OfflineFrame {
  // todo recursive read buffer for byes payload
  val offlineFrame = decodeMessageFromPeer(readChannel.readByteArray()).let { OfflineFrame.ADAPTER.decode(it) }
  val offlineFrameContent = offlineFrame.v1

  require(offlineFrameContent != null) { "OfflineFrame content not found: $offlineFrame" }

  // if message received here was a keep alive, reply and read again if is not and ack
  if (offlineFrameContent.type == V1Frame.FrameType.KEEP_ALIVE) {
    log("NearbyReceiverConnectionHandler", "Received keep alive with ack: ${offlineFrameContent.keep_alive?.ack}")

    if (offlineFrameContent.keep_alive?.ack == false) {
      replyKeepAlive(writeChannel, this)
    }

    return receiveEncryptedOfflineMessage(readChannel, writeChannel)
  }

  if (offlineFrameContent.type == V1Frame.FrameType.DISCONNECTION) {
    log("NearbyReceiverConnectionHandler", "Received DISCONNECTION from peer")
    NearbyDisconnectionObserver.recordPeerDisconnection()
    return offlineFrame
  }

  val header = offlineFrameContent.payload_transfer?.payload_header
  require(header != null) { "Payload header not found: $offlineFrame" }

  val msg = if (header.type != BYTES) {
    offlineFrame
  } else {
    val chunkBody = offlineFrameContent.payload_transfer?.payload_chunk?.body
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
  buffer: ByteArray
): OfflineFrame {

  val offlineFrameContent = offlineFrame.v1
  require(offlineFrameContent != null) { "OfflineFrame content not found: $offlineFrame" }

  val payload = offlineFrameContent.payload_transfer
  require(payload != null) { "Payload transfer not found" }

  val chunk = payload.payload_chunk
  require(chunk != null) { "Payload chunk not found" }

  if (chunk.flags!! and 1 == 1) {
    log("recursiveReadOfflineFrame", "last chunk found")

    return offlineFrame.copy(
      v1 = offlineFrameContent.copy(
        payload_transfer = payload.copy(
          payload_chunk = chunk.copy(body = (buffer).toByteString())
        )
      )
    )
  } else {
    log("recursiveReadOfflineFrame", "reading next chunk")

    val newOfflineFrame = decodeMessageFromPeer(readChannel.readByteArray()).let { OfflineFrame.ADAPTER.decode(it) }
    val newChunk = newOfflineFrame.v1?.payload_transfer?.payload_chunk
    require(newChunk != null) { "Payload chunk not found" }

    val header = payload.payload_header
    require(header?.id == payloadId) { "Payload id mismatch header.id = ${header?.id} payloadId = ${payloadId}. frame: $newOfflineFrame" }

    val newBody = newChunk.body?.toByteArray() ?: byteArrayOf()

    return recursiveReadOfflineFrame(readChannel, newOfflineFrame, payloadId, buffer + newBody)
  }

}

internal suspend fun ByteReadChannel.readByteArray(): ByteArray {
  val channel = this

  log("readByteArray", "start reading")

  if (channel.isClosedForRead) {
    throw IllegalStateException("Channel is closed")
  }

  // first 4 bytes for the size
  val sizeBytes = ByteArray(4)
  val sizeByte = channel.readFully(sizeBytes).let { sizeBytes.map { it.toUByte().toInt() } }
  val size = ((sizeByte[0] shl 24) or (sizeByte[1] shl 16) or (sizeByte[2] shl 8) or sizeByte[3])

  log("readByteArray", "have size of $size")

  // now read upstream
  val payload = ByteArray(size)
  channel.readFully(payload)

  return payload
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

/**
 * Send an encrypted DISCONNECTION offline frame, signalling a clean teardown
 * to the peer. NearDrop sends this before closing the TCP socket; without it
 * Android Quick Share treats the abrupt close as a protocol error and shows
 * a failure to the user even when the bytes arrived intact.
 */
internal suspend fun sendDisconnection(writeChannel: ByteWriteChannel, nearbyConnection: D2DConnectionContext) {
  OfflineFrame(
    version = OfflineFrame.Version.V1,
    v1 = V1Frame(
      type = V1Frame.FrameType.DISCONNECTION,
      disconnection = DisconnectionFrame(),
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
    log("NearbyShareUtils", "Sending chunk $chunkIndex chunkBody from:sizeStartRange size: $size chunkSize: ${chunk.size} total size $totalSize")

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
internal fun sendEncryptedWrappedPayload(
  source: Source,
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

    runCatching {
      source.readTo(readBuffer, size.toLong())
    }.onFailure {
      log("NearbyShareUtils","sendEncryptedWrappedPayload: error reading source", it)
      throw it
    }

    val chunkBody = readBuffer.readByteString()
    log("NearbyShareUtils","sendEncryptedWrappedPayload: Sending chunk $chunkIndex range $sizeStartRange chunkBody ${chunkBody.size} total size $totalSize")

    sendChunkWrappedPayload(
      totalSize = totalSize,
      payloadId = payloadId,
      offset = sentOffset,
      bodyChunk = chunkBody.toOkioByteString(),
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

private suspend fun sendChunkWrappedPayload(
  totalSize: Long,
  payloadId: Long,
  offset: Long,
  bodyChunk: okio.ByteString?,
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