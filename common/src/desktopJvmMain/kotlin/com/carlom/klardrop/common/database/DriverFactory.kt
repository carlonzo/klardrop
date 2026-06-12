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
      } else {
        // Run any pending migrations for existing databases.
        val currentVersion: Int = driver.executeQuery(
          identifier = null,
          sql = "PRAGMA user_version",
          mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(
              if (cursor.next().value) cursor.getLong(0)?.toInt() ?: 0 else 0
            )
          },
          parameters = 0,
        ).value
        val targetVersion = AppDatabase.Schema.version.toInt()
        if (currentVersion < targetVersion) {
          AppDatabase.Schema.migrate(
            driver,
            oldVersion = currentVersion.toLong(),
            newVersion = targetVersion.toLong(),
          )
        }
      }

      driver
    }
  }
}
