package com.carlom.klardrop.common.utils

public class Clock {
  public fun currentTimeMillis(): Long =
    kotlin.time.Clock.System.now().toEpochMilliseconds()
}
