package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.sqlite.JCBCSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = JCBCSqliteDriver("jdbc:sqlite:AppDatabase.db")
        if (!File("AppDatabase.db").exists()) {
            AppDatabase.Schema.create(driver)
        }
        return driver
    }
}
