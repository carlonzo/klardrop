package com.carlom.klardrop.common.utils

import platform.Foundation.NSUUID

actual class UUIDGenerator actual constructor() {
  actual fun generate(): String {
    return NSUUID().UUIDString
  }
}