package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.io.path.exists

actual class DriverFactory(private val databaseFolderPath: Path) {
    actual fun createDriver(): SqlDriver {
        val dbPath = Path(databaseFolderPath, "AppDatabase.db")

        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")
        if (!SystemFileSystem.exists(dbPath)) {
            SystemFileSystem.createDirectories(databaseFolderPath, mustCreate = false)

            AppDatabase.Schema.create(driver)

            require(SystemFileSystem.exists(dbPath)) {
                "Database file was not created successfully at $dbPath"
            }
        }

        return driver
    }
}
