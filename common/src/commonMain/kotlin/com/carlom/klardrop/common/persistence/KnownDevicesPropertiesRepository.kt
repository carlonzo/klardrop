package com.carlom.klardrop.common.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

interface KnownDevicesPropertiesRepository {

  val knownDevices: Flow<Map<String, DeviceInfo>>

  suspend fun saveDeviceInfo(deviceInfo: DeviceInfo)

}

internal class KnownDevicesPropertiesRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
  private val coroutines: Coroutines,
) : KnownDevicesPropertiesRepository {

  private val protoBuf = ProtoBuf

  override val knownDevices: Flow<Map<String, DeviceInfo>> = dataStore.data
    .mapLatest {
      it.asMap().map { entry ->
        val deviceInfo = protoBuf.decodeFromByteArray<DeviceInfo>(entry.value as ByteArray)
        deviceInfo.deviceId to deviceInfo
      }.toMap()
    }

  override suspend fun saveDeviceInfo(deviceInfo: DeviceInfo) {
    withContext(coroutines.ioDispatcher) {
      dataStore.edit {
        it.toMutablePreferences().putOrRemove(
          byteArrayPreferencesKey(deviceInfo.deviceId), protoBuf.encodeToByteArray(deviceInfo)
        )
      }
    }
  }

}

@Serializable
data class DeviceInfo(
  val deviceId: String,
  val lastAddress: String,
  val name: String,
  val lastAck: Long
)