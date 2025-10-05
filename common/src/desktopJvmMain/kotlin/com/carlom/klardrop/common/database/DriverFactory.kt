package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

actual class DriverFactory(private val databaseFolderPath: Path, private val disablePersistence: Boolean = false) {
  actual fun createDriver(): SqlDriver {
    return if (disablePersistence) {
      val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
      AppDatabase.Schema.create(driver)
      driver
    } else {
      val dbPath = Path(databaseFolderPath, "AppDatabase.db")

      val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
      if (!SystemFileSystem.exists(dbPath)) {
        SystemFileSystem.createDirectories(databaseFolderPath, mustCreate = false)

        AppDatabase.Schema.create(driver)

        require(SystemFileSystem.exists(dbPath)) {
          "Database file was not created successfully at $dbPath"
        }
      }

      driver
    }
  }
}
