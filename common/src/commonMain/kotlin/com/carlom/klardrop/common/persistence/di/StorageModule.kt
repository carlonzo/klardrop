package com.carlom.klardrop.common.persistence.di

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.carlom.klardrop.common.persistence.DeviceInfo
import com.carlom.klardrop.common.persistence.KnownDevicesPropertiesRepository
import com.carlom.klardrop.common.persistence.KnownDevicesPropertiesRepositoryImpl
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepositoryImpl
import com.carlom.klardrop.common.utils.Coroutines
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class StorageModule {

  private companion object {
    const val propertiesFileName = "properties.preferences_pb"
    const val knownDevicesFileName = "known_devices.preferences_pb"
  }

  private fun getPreferencesDatastore(filePath: (() -> Path)): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(produceFile = filePath)
  }

  private fun storageFilePath(rootPath: () -> String, fileName: String): Path {
    return rootPath().toPath().resolve(fileName.toPath())
  }


  fun localPropertiesRepository(coroutines: Coroutines, rootPath: (() -> String)): LocalPropertiesRepository {
    return LocalPropertiesRepositoryImpl(getPreferencesDatastore { storageFilePath(rootPath, propertiesFileName) }, coroutines)
  }

  fun knownDevicesRepository(coroutines: Coroutines, rootPath: () -> String): KnownDevicesPropertiesRepository{
    return KnownDevicesPropertiesRepositoryImpl(getPreferencesDatastore { storageFilePath(rootPath, knownDevicesFileName) }, coroutines)
  }

}