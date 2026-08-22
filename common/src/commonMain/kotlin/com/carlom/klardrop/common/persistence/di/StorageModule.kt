package com.carlom.klardrop.common.persistence.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.database.DriverFactory
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.KnownDevicesRepositoryImpl
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepositoryImpl
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageRepositoryImpl
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.nextString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import okio.FileSystem.Companion.SYSTEM_TEMPORARY_DIRECTORY
import okio.Path.Companion.toPath
import kotlin.random.Random

class StorageModule(
  private val applicationInfo: ApplicationInfo,
  private val coroutines: Coroutines,
  private val platformFileSystem: PlatformFileSystem,
  private val driverFactory: DriverFactory,
  private val clock: Clock
) {

  private val appDatabase: AppDatabase by lazy {
    AppDatabase(driverFactory.createDriver())
  }

  private companion object {
    const val propertiesFileName = "properties.preferences_pb"
    const val knownDevicesFileName = "known_devices.preferences_pb"
  }

  private fun getPreferencesDatastore(filePath: (() -> okio.Path)): DataStore<Preferences> {
    if (applicationInfo.disablePersistence) return PreferenceDataStoreFactory.createWithPath(produceFile = {
      SYSTEM_TEMPORARY_DIRECTORY.resolve(
        "klardrop_" + Random.nextString(16) + ".preferences_pb"
      )
    })

    return PreferenceDataStoreFactory.createWithPath(produceFile = { filePath() })
  }

  private fun storageFilePath(rootPath: () -> Path, fileName: String): okio.Path {
    return Path(rootPath(), fileName).toOkioPath()
  }

  fun localPropertiesRepository(): LocalPropertiesRepository {
    return LocalPropertiesRepositoryImpl(
      getPreferencesDatastore {
        storageFilePath({ platformFileSystem.getInternalStoragePath() }, propertiesFileName)
      }, coroutines
    )
  }

  fun knownDevicesRepository(): KnownDevicesRepository {
    return KnownDevicesRepositoryImpl(getPreferencesDatastore {
      storageFilePath(
        { platformFileSystem.getInternalStoragePath() },
        knownDevicesFileName
      )
    }, coroutines)
  }

  fun messageRepository(): MessageRepository {
    return MessageRepositoryImpl(
      appDatabase,
      clock,
      coroutines.ioDispatcher,
    )
  }

  private fun Path.toOkioPath(): okio.Path {
    return this.toString().toPath()
  }
}