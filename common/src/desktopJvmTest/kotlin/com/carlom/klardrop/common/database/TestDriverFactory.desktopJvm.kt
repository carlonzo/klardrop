package com.carlom.klardrop.common.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual fun createTestDriver(): app.cash.sqldelight.db.SqlDriver {
  val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
  AppDatabase.Schema.create(driver)
  return driver
}