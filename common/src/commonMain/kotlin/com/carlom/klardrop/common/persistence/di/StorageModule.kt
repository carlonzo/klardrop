package com.carlom.klardrop.common.persistence.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.KnownDevicesRepositoryImpl
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepositoryImpl
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.nextString
import okio.FileSystem.Companion.SYSTEM_TEMPORARY_DIRECTORY
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

class StorageModule(
  private val applicationInfo: ApplicationInfo
) {

  private companion object {
    const val propertiesFileName = "properties.preferences_pb"
    const val knownDevicesFileName = "known_devices.preferences_pb"
  }

  private fun getPreferencesDatastore(filePath: (() -> Path)): DataStore<Preferences> {
    if (applicationInfo.disablePersistence) return PreferenceDataStoreFactory.createWithPath(produceFile = {
      SYSTEM_TEMPORARY_DIRECTORY.resolve(
        "klardrop_" + Random.nextString(16) + ".preferences_pb"
      )
    })

    return PreferenceDataStoreFactory.createWithPath(produceFile = filePath)
  }

  private fun storageFilePath(rootPath: () -> String, fileName: String): Path {
    return rootPath().toPath().resolve(fileName.toPath())
  }


  fun localPropertiesRepository(coroutines: Coroutines, rootPath: (() -> String)): LocalPropertiesRepository {
    return LocalPropertiesRepositoryImpl(getPreferencesDatastore { storageFilePath(rootPath, propertiesFileName) }, coroutines)
  }

  fun knownDevicesRepository(coroutines: Coroutines, rootPath: () -> String): KnownDevicesRepository {
    return KnownDevicesRepositoryImpl(getPreferencesDatastore { storageFilePath(rootPath, knownDevicesFileName) }, coroutines)
  }

}