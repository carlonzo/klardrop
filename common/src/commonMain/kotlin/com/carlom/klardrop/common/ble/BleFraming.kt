package com.carlom.klardrop.common.ble

/**
 * Chunking + reassembly for the Klardrop wire format over a chunk-oriented transport
 * like BLE GATT characteristic writes.
 *
 * The wire format is identical to TCP:
 *   `[4-byte big-endian length][1-byte message type][protobuf payload]`
 * The 4-byte length covers everything after it (type + payload). The framer/reassembler
 * only deal in raw bytes — turning serialized messages into chunks and back into frames.
 * Deserialization is the caller's responsibility, so this stays a pure utility.
 */
object BleFraming {

  const val LENGTH_PREFIX_SIZE = 4

  /**
   * Prepend the 4-byte big-endian length prefix to [messagePayload] and split the result
   * into chunks no larger than [mtu]. [messagePayload] is the bytes produced by
   * `MessageSerializer.serialize(...)` — i.e. it already contains the 1-byte type id
   * followed by the protobuf body.
   *
   * @throws IllegalArgumentException if [mtu] is not positive.
   */
  fun chunk(messagePayload: ByteArray, mtu: Int): List<ByteArray> {
    require(mtu > 0) { "mtu must be > 0 (was $mtu)" }
    val framed = withLengthPrefix(messagePayload)
    if (framed.size <= mtu) return listOf(framed)

    val chunks = ArrayList<ByteArray>((framed.size + mtu - 1) / mtu)
    var offset = 0
    while (offset < framed.size) {
      val end = minOf(offset + mtu, framed.size)
      chunks.add(framed.copyOfRange(offset, end))
      offset = end
    }
    return chunks
  }

  internal fun withLengthPrefix(messagePayload: ByteArray): ByteArray {
    val size = messagePayload.size
    val out = ByteArray(LENGTH_PREFIX_SIZE + size)
    out[0] = (size ushr 24).toByte()
    out[1] = (size ushr 16).toByte()
    out[2] = (size ushr 8).toByte()
    out[3] = size.toByte()
    messagePayload.copyInto(out, LENGTH_PREFIX_SIZE)
    return out
  }
}

/**
 * Stateful reassembler that turns incoming chunks back into complete message payloads.
 *
 * Feed chunks in via [onChunk]; each call returns zero or more complete payloads that were
 * finished by the bytes in that chunk. Payloads do NOT include the length prefix — they
 * are exactly what `MessageSerializer.deserialize(...)` expects (type id + protobuf body).
 *
 * The implementation is split-agnostic: it doesn't matter whether the length prefix is
 * delivered across multiple chunks, or whether one chunk contains multiple complete frames.
 */
class BleReassembler {

  // Rolling buffer of unprocessed bytes. We avoid repeated allocations by appending and
  // only compacting when we consume a full frame.
  private var buffer: ByteArray = ByteArray(0)

  /**
   * Push [chunk] into the reassembler and return any complete payloads it finished.
   * Never returns null; returns an empty list when no frame has completed yet.
   */
  fun onChunk(chunk: ByteArray): List<ByteArray> {
    if (chunk.isEmpty()) return emptyList()

    buffer = if (buffer.isEmpty()) chunk.copyOf() else buffer + chunk

    val completed = mutableListOf<ByteArray>()
    while (true) {
      if (buffer.size < BleFraming.LENGTH_PREFIX_SIZE) break

      val length = (buffer[0].toInt() and 0xFF shl 24) or
        (buffer[1].toInt() and 0xFF shl 16) or
        (buffer[2].toInt() and 0xFF shl 8) or
        (buffer[3].toInt() and 0xFF)

      require(length >= 0) { "Negative frame length decoded: $length" }

      val totalFrameSize = BleFraming.LENGTH_PREFIX_SIZE + length
      if (buffer.size < totalFrameSize) break

      completed += buffer.copyOfRange(BleFraming.LENGTH_PREFIX_SIZE, totalFrameSize)
      buffer = if (buffer.size == totalFrameSize) {
        ByteArray(0)
      } else {
        buffer.copyOfRange(totalFrameSize, buffer.size)
      }
    }
    return completed
  }

  /** Number of unprocessed bytes currently held (for tests + diagnostics). */
  val bufferedBytes: Int get() = buffer.size
}
