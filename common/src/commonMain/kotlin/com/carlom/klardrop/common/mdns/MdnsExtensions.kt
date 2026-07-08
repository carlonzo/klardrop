package com.carlom.klardrop.common.mdns

/**
 * Converts an array of byte values representing DNS-SD TXT records into a Map of key-value pairs.
 *
 * Wire format (RFC 6763): a sequence of length-prefixed strings, each `key=value` (or a bare key).
 *
 * **Bugsnag root cause (macOS SIGABRT / IndexOutOfBoundsException "index: 3, size: 3"):**
 * The previous implementation trusted the length byte and called [ByteArray.copyOfRange] without
 * checking remaining buffer size. On Kotlin/Native, reading past the end surfaces as
 * `IndexOutOfBoundsException: index: N, size: N` (exact message from production), then
 * [CoroutineExceptionHandler] rethrow aborted the process. Truncated or mis-sized TXT
 * (malformed peer, UTF-8 length mismatch on publish) is common during Bonjour resolve.
 *
 * This parser is defensive: bad/truncated records are skipped or stop the parse — they never throw.
 */
internal fun txtByteToMap(array: ByteArray): Map<String, String> {
  if (array.isEmpty()) return emptyMap()

  val result = linkedMapOf<String, String>()
  var index = 0
  while (index < array.size) {
    // Length is an unsigned 8-bit field (0..255). Signed Byte.toInt() would turn
    // lengths >= 128 into negative values and break range arithmetic.
    val dataLength = array[index].toInt() and 0xFF
    index += 1

    if (dataLength == 0) continue

    val end = index + dataLength
    if (end > array.size) {
      // Truncated record — cannot safely read; stop without throwing.
      break
    }

    val entry = array.copyOfRange(index, end)
    index = end

    val sep = entry.indexOf(TXT_SEPARATOR)
    if (sep <= 0) continue // bare flag key, empty key, or no '=' — skip non key=value entries

    val key = entry.decodeToString(startIndex = 0, endIndex = sep)
    val value = entry.decodeToString(startIndex = sep + 1, endIndex = entry.size)
    if (key.isNotEmpty()) {
      result[key] = value
    }
  }
  return result
}

private const val TXT_SEPARATOR = '='.code.toByte()
