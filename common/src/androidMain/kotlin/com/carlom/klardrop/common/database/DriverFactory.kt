package com.carlom.klardrop.common.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context, private val disablePersistence: Boolean = false) {
  actual fun createDriver(): SqlDriver {
    return if (disablePersistence) {
      AndroidSqliteDriver(AppDatabase.Schema, context, null) // null database name creates in-memory database
    } else {
      AndroidSqliteDriver(AppDatabase.Schema, context, "AppDatabase.db")
    }
  }
}
