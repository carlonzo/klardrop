package com.carlom.klardrop.common.persistence

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.klardrop.common.persistence.KlardropDatabase

actual class DatabaseDriverFactory(private val context: Context) {
  actual fun createDriver(): SqlDriver {
    return AndroidSqliteDriver(
      schema = KlardropDatabase.Schema.synchronous(),
      context = context,
      name = DB_NAME
    )
  }
}