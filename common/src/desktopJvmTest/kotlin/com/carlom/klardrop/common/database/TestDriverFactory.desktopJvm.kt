package com.carlom.klardrop.common.database

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