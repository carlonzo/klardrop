package com.carlom.klardrop.common.utils

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual class Clock {
  actual fun currentTimeMillis(): Long {
    return NSDate().timeIntervalSince1970.toLong() * 1000
  }

}