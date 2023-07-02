package com.carlom.klardrop.common.utils

internal fun Iterable<Int>.toByteArray(): ByteArray {
  val result = ByteArray(count())

  this.forEachIndexed { index, element ->
    result[index] = element.toByte()
  }

  return result
}