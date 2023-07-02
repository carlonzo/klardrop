package com.carlom.klardrop.common.mdns

/**
 * Converts an array of byte values representing TXT records into a Map of key-value pairs.
 *
 * @param array The array of byte values representing TXT records.
 * @return A Map containing the key-value pairs extracted from the array.
 * @throws IllegalArgumentException if an invalid TXT record is encountered.
 */
internal fun txtByteToMap(array: ByteArray): Map<String, String> {
  val list = mutableListOf<ByteArray>()

  fun getTxt(array: ByteArray, firstIndex: Int): ByteArray {
    val dataLength = array[firstIndex].toInt()
    return array.copyOfRange(firstIndex + 1, firstIndex + dataLength + 1)
  }

  var index = 0
  while (index < array.size) {
    val txt = getTxt(array, index)
    list.add(txt)
    index += txt.size + 1
  }



  return list.filter { it.indexOf(TXT_SEPARATOR) != -1 }.associate {

    val split = it.indexOf(TXT_SEPARATOR)
    require(split != -1) { "Invalid TXT record: ${it.decodeToString()}" }

    val key = it.copyOfRange(0, split)
    val value = it.copyOfRange(split + 1, it.size)

    key.decodeToString() to value.decodeToString()
  }
}

private const val TXT_SEPARATOR = '='.code.toByte()