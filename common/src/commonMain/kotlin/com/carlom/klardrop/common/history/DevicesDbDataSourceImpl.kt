package com.carlom.klardrop.common.history

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.klardrop.common.persistence.Devices_db
import com.klardrop.common.persistence.KlardropDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.invoke

class DevicesDbDataSourceImpl(
  private val klardropDatabase: Lazy<KlardropDatabase>,
  private val coroutines: Coroutines
) : DevicesDbDataSource {

  private val devicesQueries by lazy { klardropDatabase.value.devicesQueries }
  override suspend fun getDevice(deviceId: String): DeviceInfo? = coroutines.ioDispatcher {
    devicesQueries.select_device(deviceId).awaitAsOneOrNull()?.toDeviceInfo()
  }

  override suspend fun getDeviceAsFlow(deviceId: String): Flow<DeviceInfo> {
    return devicesQueries.select_device(deviceId).asFlow()
      .mapToOne(coroutines.ioDispatcher)
      .map { it.toDeviceInfo() }
  }

  private fun Devices_db.toDeviceInfo(): DeviceInfo{
    return DeviceInfo(
      deviceId = device_id,
      name = name,
      deviceType = DeviceType.fromId(type.toByte()),
      osType = OsType.fromId(os.toByte())
    )
  }
}