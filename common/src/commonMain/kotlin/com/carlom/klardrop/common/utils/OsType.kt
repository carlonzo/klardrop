package com.carlom.klardrop.common.utils

enum class OsType(val id: Byte) {
  ANDROID(1), APPLE(2), LINUX(3), WINDOWS(4), UNKNOWN(15);

  companion object {
    fun fromId(id: Byte): OsType {
      return OsType.values().first { it.id == id }
    }

    fun fromId(id: Int): OsType {
      val b = id.toByte()
      return OsType.values().first { it.id == b }
    }
  }
}