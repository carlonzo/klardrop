package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Opens the database; if the existing file can't be opened with the current schema — e.g. it was
 * written by a newer build ("Database version N newer than config version M") or is corrupt —
 * deletes it and opens a fresh one instead of crashing on launch.
 *
 * This is the very first public release: an incompatible on-disk database is disposable, not
 * something to migrate.
 */
internal fun openOrRecreate(open: () -> SqlDriver, deleteDatabase: () -> Unit): SqlDriver {
    val driver = open()
    return try {
        // Drivers connect lazily, so force a query to run the version/schema check now.
        driver.executeQuery(null, "PRAGMA user_version", { QueryResult.Value(Unit) }, 0)
        driver
    } catch (e: Exception) {
        runCatching { driver.close() }
        deleteDatabase()
        open()
    }
}
