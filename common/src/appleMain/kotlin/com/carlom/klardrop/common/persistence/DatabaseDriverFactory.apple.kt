package com.carlom.klardrop.common.persistence

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.klardrop.common.persistence.KlardropDatabase

actual class DatabaseDriverFactory {
  actual fun createDriver(): SqlDriver {
    return NativeSqliteDriver(
      schema = KlardropDatabase.Schema.synchronous(),
      name = DB_NAME,
    )
  }
}