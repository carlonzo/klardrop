package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import com.carlom.klardrop.common.utils.LinuxPaths
import com.carlom.klardrop.common.utils.ensureDirectory0700
import kotlinx.io.files.Path
import platform.posix.unlink

private const val DB_NAME = "AppDatabase.db"

actual class DriverFactory(
  private val databaseFolderPath: Path = Path(LinuxPaths.databasesDir),
  private val disablePersistence: Boolean = false,
) {
  constructor(disablePersistence: Boolean) : this(Path(LinuxPaths.databasesDir), disablePersistence)

  actual fun createDriver(): SqlDriver {
    return if (disablePersistence) {
      val schema = AppDatabase.Schema
      NativeSqliteDriver(
        DatabaseConfiguration(
          name = DB_NAME,
          version = schema.version.toInt(),
          create = { connection ->
            wrapConnection(connection) { schema.create(it) }
          },
          inMemory = true,
        )
      )
    } else {
      val basePathStr = databaseFolderPath.toString()
      ensureDirectory0700(basePathStr)
      val schema = AppDatabase.Schema

      openOrRecreate(
        open = {
          NativeSqliteDriver(
            DatabaseConfiguration(
              name = DB_NAME,
              version = schema.version.toInt(),
              create = { connection ->
                wrapConnection(connection) { schema.create(it) }
              },
              upgrade = { connection, oldVersion, newVersion ->
                wrapConnection(connection) { schema.migrate(it, oldVersion.toLong(), newVersion.toLong()) }
              },
              extendedConfig = DatabaseConfiguration.Extended(basePath = basePathStr),
              inMemory = false,
            )
          )
        },
        deleteDatabase = {
          unlink("$basePathStr/$DB_NAME")
        },
      ).also(::healSchemaDrift)
    }
  }
}
