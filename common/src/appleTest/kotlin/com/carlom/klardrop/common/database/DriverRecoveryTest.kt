package com.carlom.klardrop.common.database

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reproduces the launch crash "Database version 2 newer than config version 1": a database written
 * by a newer build must not abort the app. openOrRecreate should wipe it and open a fresh one.
 */
class DriverRecoveryTest {
    private val name = "recovery_test.db"

    @BeforeTest
    fun cleanBefore() = DatabaseFileContext.deleteDatabase(name)

    @AfterTest
    fun cleanAfter() = DatabaseFileContext.deleteDatabase(name)

    @Test
    fun recreatesDatabaseWrittenByNewerSchema() {
        // Simulate a future build: same tables, a higher version number on disk.
        NativeSqliteDriver(
            DatabaseConfiguration(
                name = name,
                version = (AppDatabase.Schema.version + 1).toInt(),
                create = { conn -> wrapConnection(conn) { AppDatabase.Schema.create(it) } },
            )
        ).close()

        // Opening at the current schema version would throw; recovery must wipe + reopen instead.
        val driver = openOrRecreate(
            open = { NativeSqliteDriver(AppDatabase.Schema, name) },
            deleteDatabase = { DatabaseFileContext.deleteDatabase(name) },
        )

        // Fresh database is usable.
        AppDatabase(driver).messageQueries.getUnreadCountForDevice("x").executeAsOne()
        driver.close()
    }
}
