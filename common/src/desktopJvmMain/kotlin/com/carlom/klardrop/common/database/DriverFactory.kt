package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.carlom.klardrop.common.database.AppDatabase
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:AppDatabase.db")
        if (!File("AppDatabase.db").exists()) {
            AppDatabase.Schema.create(driver)
        }
        return driver
    }
}
