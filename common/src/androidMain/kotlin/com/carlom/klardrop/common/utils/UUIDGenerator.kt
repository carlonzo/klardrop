package com.carlom.klardrop.common.utils

import java.util.*

actual class UUIDGenerator actual constructor() {
  actual fun generate(): String {
    return UUID.randomUUID().toString()
  }
}