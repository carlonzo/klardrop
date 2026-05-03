package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration

actual class DriverFactory(private val disablePersistence: Boolean = false) {
  actual fun createDriver(): SqlDriver {
    return if (disablePersistence) {
      val schema = AppDatabase.Schema
      NativeSqliteDriver(
        DatabaseConfiguration(
          name = "AppDatabase.db",
          version = schema.version.toInt(),
          create = { connection ->
            wrapConnection(connection) { schema.create(it) }
          },
          inMemory = true
        )
      )
    } else {
      NativeSqliteDriver(AppDatabase.Schema, "AppDatabase.db")
    }
  }
}
