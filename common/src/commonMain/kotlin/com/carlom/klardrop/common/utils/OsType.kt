package com.carlom.klardrop.common.utils

public enum class OsType(public val nearbyId: Byte) {
  ANDROID(1), APPLE(2), LINUX(3), WINDOWS(4), UNKNOWN(15);

  public companion object {
    public fun fromId(id: Byte): OsType {
      return entries.firstOrNull { it.nearbyId == id } ?: UNKNOWN
    }

    public fun fromId(id: Int): OsType {
      val b = id.toByte()
      return entries.firstOrNull { it.nearbyId == b } ?: UNKNOWN
    }
  }
}