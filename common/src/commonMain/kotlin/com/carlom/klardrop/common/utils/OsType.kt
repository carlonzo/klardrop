package com.carlom.klardrop.common.utils

enum class OsType(val nearbyId: Byte) {
  ANDROID(1), APPLE(2), LINUX(3), WINDOWS(4), UNKNOWN(15);

  companion object {
    fun fromId(id: Byte): OsType {
      return entries.first { it.nearbyId == id }
    }

    fun fromId(id: Int): OsType {
      val b = id.toByte()
      return entries.first { it.nearbyId == b }
    }
  }
}