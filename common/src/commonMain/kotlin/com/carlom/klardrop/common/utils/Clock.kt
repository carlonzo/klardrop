package com.carlom.klardrop.common.utils

class Clock {
  fun currentTimeMillis(): Long =
    kotlin.time.Clock.System.now().toEpochMilliseconds()
}
