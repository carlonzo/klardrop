package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.DeviceType

expect class InternalPlatformDependencies {

  fun getRootPath(): String
  fun getDeviceName(): String

  fun deviceType(): DeviceType

}