package com.carlom.klardrop.common.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
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

}

internal class LocalPropertiesRepositoryImpl(
  private val dataStore: DataStore<Preferences>,
  private val coroutines: Coroutines
): LocalPropertiesRepository {

  private val deviceIdKey = stringPreferencesKey("device_id")

  override val properties: Flow<KlardropProperties> = dataStore.data.mapLatest {
    KlardropProperties(
      it[deviceIdKey] ?: ""
    )
  }

  override suspend fun getProperty(): KlardropProperties {
    return properties.first()
  }

  override suspend fun save(properties: KlardropProperties) {
    withContext( coroutines.ioDispatcher) {
      dataStore.edit {
        it.putOrRemove(deviceIdKey, properties.deviceId)
      }
    }
  }

}

internal fun <T>MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?){
  if (value == null) remove(key)
  else set(key, value)
}

data class KlardropProperties(
  val deviceId: String
)