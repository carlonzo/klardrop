package com.carlom.klardrop.common.database

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration

actual fun createTestDriver(): app.cash.sqldelight.db.SqlDriver {
  val schema = AppDatabase.Schema
  return NativeSqliteDriver(
    DatabaseConfiguration(
      name = "test.db",
      version = schema.version.toInt(),
      create = { connection ->
        wrapConnection(connection) { schema.create(it) }
      },
      inMemory = true
    )
  )
}

