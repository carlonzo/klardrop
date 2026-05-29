package com.carlom.klardrop.common.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

interface LocalPropertiesRepository {

  val properties: Flow<KlardropProperties>

  suspend fun getProperty(): KlardropProperties
  suspend fun save(properties: KlardropProperties)
  suspend fun saveCustomDeviceName(customDeviceName: String?)

  /** Opt-in: keep the device discoverable/connectable while the app is backgrounded
   *  (Android backs this with a foreground service). */
  suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean)

}

internal class LocalPropertiesRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
  private val coroutines: Coroutines
): LocalPropertiesRepository {

  private val deviceIdKey = stringPreferencesKey("device_id")
  private val customDeviceNameKey = stringPreferencesKey("custom_device_name")
  private val backgroundDiscoveryEnabledKey = booleanPreferencesKey("background_discovery_enabled")

  override val properties: Flow<KlardropProperties> = dataStore.data.mapLatest {
    KlardropProperties(
      deviceId = it[deviceIdKey] ?: "",
      customDeviceName = it[customDeviceNameKey],
      backgroundDiscoveryEnabled = it[backgroundDiscoveryEnabledKey] ?: false,
    )
  }

  override suspend fun getProperty(): KlardropProperties {
    return properties.first()
  }

  override suspend fun save(properties: KlardropProperties) {
    withContext( coroutines.ioDispatcher) {
      dataStore.edit {
        it.putOrRemove(deviceIdKey, properties.deviceId)
        it.putOrRemove(customDeviceNameKey, properties.customDeviceName)
        it[backgroundDiscoveryEnabledKey] = properties.backgroundDiscoveryEnabled
      }
    }
  }

  override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {
    withContext(coroutines.ioDispatcher) {
      dataStore.edit { it[backgroundDiscoveryEnabledKey] = enabled }
    }
  }

  override suspend fun saveCustomDeviceName(customDeviceName: String?) {
    withContext(coroutines.ioDispatcher) {
      dataStore.edit { preferences ->
        preferences.putOrRemove(customDeviceNameKey, customDeviceName)
      }
    }
  }

}

internal fun <T>MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?){
  if (value == null) remove(key)
  else set(key, value)
}

data class KlardropProperties(
  val deviceId: String,
  val customDeviceName: String? = null,
  val backgroundDiscoveryEnabled: Boolean = false,
)