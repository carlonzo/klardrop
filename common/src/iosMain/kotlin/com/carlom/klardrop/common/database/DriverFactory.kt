package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory(private val disablePersistence: Boolean = false) {
  actual fun createDriver(): SqlDriver {
    return if (disablePersistence) {
      NativeSqliteDriver(AppDatabase.Schema, null) // null database name creates in-memory database
    } else {
      NativeSqliteDriver(AppDatabase.Schema, "AppDatabase.db")
    }
  }
}
