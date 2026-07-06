package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

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

/**
 * Applies any pending SQLDelight migrations (`.sqm` files) to an already-open [driver] whose
 * on-disk `PRAGMA user_version` is behind [schema]'s current version, then advances
 * `user_version` to match.
 *
 * `AndroidSqliteDriver` and `NativeSqliteDriver` do this automatically as part of their own
 * open/upgrade callback (comparing `user_version` against `schema.version` before we ever see a
 * query). Desktop's `JdbcSqliteDriver` has no such callback — it happily opens an existing file
 * whatever schema it holds — so `DriverFactory` (desktopJvm) calls this explicitly whenever it
 * opens a database file that already existed on disk, so a same-numbered-but-drifted schema
 * (e.g. an older install missing a column added by a later `.sq` change) gets migrated instead of
 * crashing on the first query that touches the new column.
 */
internal fun migrateIfNeeded(driver: SqlDriver, schema: SqlSchema<QueryResult.Value<Unit>>) {
    val currentVersion = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
        cursor.next()
        QueryResult.Value(cursor.getLong(0) ?: 0L)
    }, 0).value
    if (currentVersion < schema.version) {
        schema.migrate(driver, currentVersion, schema.version)
        driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
    }
}
