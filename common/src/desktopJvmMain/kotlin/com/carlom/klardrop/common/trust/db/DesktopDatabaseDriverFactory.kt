package com.carlom.klardrop.common.trust.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DatabaseDriverFactory {
    actual fun createDriver(): SqlDriver {
        val dbPath = File(System.getProperty("user.home"), ".klardrop/trust.db")
        dbPath.parentFile.mkdirs()
        
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.absolutePath}")
        TrustDatabase.Schema.create(driver)
        return driver
    }
}