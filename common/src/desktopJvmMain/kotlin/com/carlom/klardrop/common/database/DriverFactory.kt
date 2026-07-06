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
      val dbAlreadyExisted = SystemFileSystem.exists(dbPath)

      val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
      if (!dbAlreadyExisted) {
        SystemFileSystem.createDirectories(databaseFolderPath, mustCreate = false)

        AppDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA user_version = ${AppDatabase.Schema.version}", 0)

        require(SystemFileSystem.exists(dbPath)) {
          "Database file was not created successfully at $dbPath"
        }
      } else {
        // JdbcSqliteDriver doesn't compare user_version against the schema on open the way
        // AndroidSqliteDriver/NativeSqliteDriver do — an existing file just opens as-is however
        // stale its schema. Run any pending migrations explicitly so a schema change (e.g. a new
        // column) doesn't crash the first query that touches it on an existing install.
        migrateIfNeeded(driver, AppDatabase.Schema)
      }

      driver
    }
  }
}