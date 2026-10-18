package com.carlom.klardrop.common.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

public interface KnownDevicesRepository {

  public val knownDevices: Flow<Map<String, DeviceInfo>>

  public suspend fun addKnownDevice(deviceInfo: DeviceInfo): Unit
  public suspend fun removeKnownDevice(deviceId: String): Unit
}

internal class KnownDevicesRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
  private val coroutines: Coroutines,
) : KnownDevicesRepository {

  private val protoBuf = ProtoBuf

  override val knownDevices: Flow<Map<String, DeviceInfo>> = dataStore.data
    .mapLatest {
      it.asMap().map { entry ->
        val deviceInfo = protoBuf.decodeFromByteArray<DeviceInfo>(entry.value as ByteArray)
        deviceInfo.deviceId to deviceInfo
      }.toMap()
    }.onStart { emptyMap<String, DeviceInfo>() }

  override suspend fun addKnownDevice(deviceInfo: DeviceInfo) {
    withContext(coroutines.ioDispatcher) {
      dataStore.edit {
        it.putOrRemove(
          byteArrayPreferencesKey(deviceInfo.deviceId), protoBuf.encodeToByteArray(deviceInfo)
        )
      }
    }
  }

  override suspend fun removeKnownDevice(deviceId: String) {
    withContext(coroutines.ioDispatcher) {
      dataStore.edit {
        it.remove(byteArrayPreferencesKey(deviceId))
      }
    }
  }
}
