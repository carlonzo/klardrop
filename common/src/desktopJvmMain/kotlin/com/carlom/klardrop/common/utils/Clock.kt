package com.carlom.klardrop.common.utils

actual class Clock {
  actual fun currentTimeMillis(): Long {
    return System.currentTimeMillis()
  }

}