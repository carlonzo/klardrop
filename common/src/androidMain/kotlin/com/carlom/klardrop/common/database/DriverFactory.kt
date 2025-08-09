package com.carlom.klardrop.common.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        return try {
            // First attempt: create driver normally
            val driver = AndroidSqliteDriver(
                schema = AppDatabase.Schema,
                context = context,
                name = "AppDatabase.db"
            )
            
            // Test if schema exists by trying to execute a simple query
            // This will fail if the table doesn't exist
            driver.execute(null, "SELECT 1 FROM device_keypair LIMIT 1", 0)
            
            driver
        } catch (_: Exception) {
            // If the query fails, the database is missing the schema
            // Delete the database and create a fresh one
            context.deleteDatabase("AppDatabase.db")
            
            AndroidSqliteDriver(
                schema = AppDatabase.Schema,
                context = context,
                name = "AppDatabase.db"
            )
        }
    }
}
