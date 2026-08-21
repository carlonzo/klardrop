package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext

private const val DB_NAME = "AppDatabase.db"

actual class DriverFactory(private val disablePersistence: Boolean = false) {
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
          inMemory = true
        )
      )
    } else {
      openOrRecreate(
        open = { NativeSqliteDriver(AppDatabase.Schema, DB_NAME) },
        deleteDatabase = { DatabaseFileContext.deleteDatabase(DB_NAME) },
      ).also(::healSchemaDrift)
    }
  }
}
