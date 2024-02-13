package com.carlom.klardrop.common.history

import com.carlom.klardrop.common.discovery.DeviceInfo
import kotlinx.coroutines.flow.Flow

interface DevicesDbDataSource {
  suspend fun getDevice(deviceId: String): DeviceInfo?
  suspend fun getDeviceAsFlow(deviceId: String): Flow<DeviceInfo>

}