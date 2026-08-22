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

/**
 * A column that some release added to an already-existing table via `ALTER TABLE ... ADD COLUMN`.
 *
 * [type] is the column's full definition suffix (type plus any default), i.e. everything after the
 * name in the `ADD COLUMN` clause. Only ever additive and nullable/defaulted — SQLite can add such
 * a column to a populated table in place.
 */
private data class AdditiveColumn(val table: String, val name: String, val type: String)

/**
 * Every column added to an existing table over this database's lifetime, in the order it was added.
 *
 * These are re-asserted on open (see [healSchemaDrift]) instead of being trusted to `user_version`
 * alone, because `user_version` has already proven ambiguous here: `1.sqm` originally read
 * `ALTER TABLE messages ADD COLUMN send_status ...`, was later deleted, then re-added with a
 * completely different body (`ADD COLUMN message_id INTEGER`). Both bodies stamp the database
 * `user_version = 2`, so two mutually incompatible "v2" schemas exist on real installs:
 *
 *  - installs that ran the send_status migration have `send_status` but no `message_id`
 *  - installs created after the rewrite have `message_id` (from `Message.sq`'s CREATE TABLE)
 *
 * A version-only check sees "2 == 2, nothing to do" for both and leaves the first kind missing
 * `message_id` forever — every inbound and outbound TEXT then fails with
 * "table messages has no column named message_id". Keep appending to this list whenever a `.sqm`
 * adds a column; never rewrite an existing entry.
 */
private val ADDITIVE_COLUMNS = listOf(
    AdditiveColumn("messages", "send_status", "TEXT DEFAULT NULL"),
    AdditiveColumn("messages", "message_id", "INTEGER"),
)

/** Column names of [table], or an empty set if the table does not exist yet. */
private fun columnsOf(driver: SqlDriver, table: String): Set<String> =
    driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
        val names = mutableSetOf<String>()
        while (cursor.next().value) {
            cursor.getString(1)?.let(names::add)
        }
        QueryResult.Value(names)
    }, 0).value

/**
 * Re-asserts every [ADDITIVE_COLUMNS] entry against the open database, adding any the on-disk
 * schema is missing.
 *
 * Idempotent and cheap (one `PRAGMA table_info` per table), so it runs on every open of a
 * persistent database on every platform — the `user_version` ambiguity described on
 * [ADDITIVE_COLUMNS] affects Android and iOS installs exactly as it does desktop ones, even though
 * their drivers run `.sqm` migrations automatically.
 */
internal fun healSchemaDrift(driver: SqlDriver) {
    for (table in ADDITIVE_COLUMNS.map { it.table }.distinct()) {
        val existing = columnsOf(driver, table)
        if (existing.isEmpty()) continue // table not created yet; CREATE TABLE already has the column
        ADDITIVE_COLUMNS
            .filter { it.table == table && it.name !in existing }
            .forEach { driver.execute(null, "ALTER TABLE ${it.table} ADD COLUMN ${it.name} ${it.type}", 0) }
    }
}
