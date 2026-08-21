package com.carlom.klardrop.common.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Repro/regression for issues 6/7 (docs/connection-review.md remediation round 1): the
 * `message_id` column was added to `Message.sq`'s `messages` table with no `.sqm` migration and
 * no schema-version bump, so an existing on-disk database (from before that column existed)
 * would open successfully at the same schema version and then crash on the first `insert` with
 * "table messages has no column named message_id".
 *
 * This test hand-builds a database exactly as an existing install's on-disk file would look —
 * the schema BEFORE `message_id` existed, at `PRAGMA user_version = 1` — then drives it through
 * [migrateIfNeeded] (what desktopJvm's `DriverFactory` now calls for every pre-existing database
 * file) and asserts an outgoing TEXT insert using `message_id` succeeds afterward.
 */
class DriverMigrationTest {

  @Test
  fun migrateIfNeeded_bringsPreMessageIdDatabaseUpToDate() = runBlocking {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    // The pre-message_id (schema v1) shape: send_status exists, message_id does not.
    driver.execute(
      null,
      """
      CREATE TABLE file_transfers (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          uuid TEXT NOT NULL UNIQUE,
          file_name TEXT NOT NULL,
          file_path TEXT NOT NULL,
          total_size INTEGER NOT NULL,
          transferred_size INTEGER NOT NULL DEFAULT 0,
          status TEXT NOT NULL,
          mime_type TEXT NOT NULL DEFAULT 'application/octet-stream'
      )
      """.trimIndent(),
      0,
    )
    driver.execute(
      null,
      """
      CREATE TABLE messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          remote_device_id TEXT NOT NULL,
          content TEXT NOT NULL,
          timestamp INTEGER NOT NULL,
          is_sender INTEGER NOT NULL,
          message_type TEXT NOT NULL,
          file_transfer_id INTEGER,
          is_read INTEGER NOT NULL DEFAULT 0,
          mime_type TEXT NOT NULL DEFAULT 'text/plain',
          send_status TEXT DEFAULT NULL,
          FOREIGN KEY(file_transfer_id) REFERENCES file_transfers(id)
      )
      """.trimIndent(),
      0,
    )
    driver.execute(null, "PRAGMA user_version = 1", 0)

    // Sanity check: this really does reproduce the crash symptom pre-migration.
    assertFailsWith<Exception>(
      "expected the pre-migration schema to reject a message_id column reference",
    ) {
      driver.execute(
        null,
        "INSERT INTO messages (remote_device_id, content, timestamp, is_sender, message_type, message_id) " +
          "VALUES ('device-x', 'hi', 1, 1, 'TEXT', 99)",
        0,
      )
    }

    migrateIfNeeded(driver, AppDatabase.Schema)

    // The exact write Messenger.send performs for the first outgoing TEXT on an existing install.
    val db = AppDatabase(driver)
    db.messageQueries.insert(
      remote_device_id = "device-1",
      content = "hello after migration",
      timestamp = 1L,
      is_sender = 1L,
      message_type = "TEXT",
      file_transfer_id = null,
      is_read = 0L,
      mime_type = "text/plain",
      send_status = "SENDING",
      message_id = 42L,
    ).await()

    val rows = db.messageQueries.getMessagesForDevice("device-1", 10).executeAsList()
    assertEquals(1, rows.size, "insert using the newly-migrated message_id column must succeed")
    assertEquals(42L, rows.first().message_id)
    assertEquals("SENDING", rows.first().send_status)

    driver.close()
  }

  /**
   * Repro/regression for the second, nastier shape of the same bug: `1.sqm` was later deleted and
   * re-added with a *different* body, so `user_version = 2` no longer identifies one schema.
   *
   * An install that ran the original `1.sqm` (`ADD COLUMN send_status`) sits at `user_version = 2`
   * with `send_status` but no `message_id`. [migrateIfNeeded] correctly sees 2 == 2 and does
   * nothing, so every inbound TEXT kept failing with "table messages has no column named
   * message_id" — the accepted transfer was ACK_REJECTED back to the sender and never stored.
   *
   * This hand-builds that exact on-disk shape and asserts [healSchemaDrift] repairs it.
   */
  @Test
  fun healSchemaDrift_repairsSendStatusEraDatabaseStuckAtVersion2() = runBlocking {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    // Verbatim `sqlite_master` shape of a real install migrated by the original send_status
    // `1.sqm`: send_status appended by ALTER, no message_id, user_version already at 2.
    driver.execute(
      null,
      """
      CREATE TABLE messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          remote_device_id TEXT NOT NULL,
          content TEXT NOT NULL,
          timestamp INTEGER NOT NULL,
          is_sender INTEGER NOT NULL,
          message_type TEXT NOT NULL,
          file_transfer_id INTEGER,
          is_read INTEGER NOT NULL DEFAULT 0,
          mime_type TEXT NOT NULL DEFAULT 'text/plain', send_status TEXT DEFAULT NULL
      )
      """.trimIndent(),
      0,
    )
    driver.execute(null, "PRAGMA user_version = 2", 0)

    // The version check alone is a no-op here — that is precisely why the bug survived it.
    migrateIfNeeded(driver, AppDatabase.Schema)
    healSchemaDrift(driver)

    val db = AppDatabase(driver)
    // The write TextMessageHandler.handleIncoming performs for an accepted inbound TEXT.
    db.messageQueries.insert(
      remote_device_id = "device-1",
      content = "hi",
      timestamp = 1L,
      is_sender = 0L,
      message_type = "TEXT",
      file_transfer_id = null,
      is_read = 0L,
      mime_type = "text/plain",
      send_status = null,
      message_id = null,
    ).await()

    val rows = db.messageQueries.getMessagesForDevice("device-1", 10).executeAsList()
    assertEquals(1, rows.size, "inbound TEXT must persist after the drift repair")
    assertEquals("hi", rows.first().content)

    driver.close()
  }

  /** The repair must be a no-op on a database that already has every column. */
  @Test
  fun healSchemaDrift_isIdempotentOnACurrentSchema() = runBlocking {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    AppDatabase.Schema.create(driver).await()

    healSchemaDrift(driver)
    healSchemaDrift(driver)

    val db = AppDatabase(driver)
    db.messageQueries.insert(
      remote_device_id = "device-1",
      content = "hi",
      timestamp = 1L,
      is_sender = 0L,
      message_type = "TEXT",
      file_transfer_id = null,
      is_read = 0L,
      mime_type = "text/plain",
      send_status = null,
      message_id = 7L,
    ).await()

    assertEquals(7L, db.messageQueries.getMessagesForDevice("device-1", 10).executeAsList().first().message_id)

    driver.close()
  }

  /**
   * Pins every `messages` shape that exists on a real install, and asserts the open path converges
   * all of them onto the current schema.
   *
   * The four shapes exist because `1.sqm` was written, deleted, then re-added with a different
   * body, so `PRAGMA user_version` does not identify a schema (see `ADDITIVE_COLUMNS`). Note rows
   * 2 and 4 are BOTH stamped version 2 while disagreeing about `message_id` — that is why no
   * sequential `.sqm` chain can repair this: a `2.sqm` adding `message_id` fixes row 2 and fails
   * row 4 with "duplicate column name: message_id".
   */
  @Test
  fun everyKnownOnDiskShapeConvergesToTheCurrentSchema() = runBlocking {
    data class Shape(val label: String, val version: Int, val extraColumns: String)

    val shapes = listOf(
      // Created before the send_status release, never upgraded.
      Shape("v1, neither column", 1, ""),
      // Upgraded by the send_status release's 1.sqm. This is the shape that was failing.
      Shape("v2, send_status only", 2, ", send_status TEXT DEFAULT NULL"),
      // Created fresh while 1.sqm was deleted: send_status inline in CREATE TABLE, version back to 1.
      Shape("v1, send_status only", 1, ", send_status TEXT DEFAULT NULL"),
      // Created fresh after 1.sqm was re-added with the message_id body.
      Shape("v2, both columns", 2, ", send_status TEXT DEFAULT NULL, message_id INTEGER"),
    )

    for (shape in shapes) {
      val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
      driver.execute(
        null,
        """
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            remote_device_id TEXT NOT NULL,
            content TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            is_sender INTEGER NOT NULL,
            message_type TEXT NOT NULL,
            file_transfer_id INTEGER,
            is_read INTEGER NOT NULL DEFAULT 0,
            mime_type TEXT NOT NULL DEFAULT 'text/plain'${shape.extraColumns}
        )
        """.trimIndent(),
        0,
      )
      driver.execute(null, "PRAGMA user_version = ${shape.version}", 0)

      // Exactly what DriverFactory (desktopJvm) does for a database file that already existed.
      migrateIfNeeded(driver, AppDatabase.Schema)
      healSchemaDrift(driver)

      val db = AppDatabase(driver)
      db.messageQueries.insert(
        remote_device_id = "device-1",
        content = "hi",
        timestamp = 1L,
        is_sender = 0L,
        message_type = "TEXT",
        file_transfer_id = null,
        is_read = 0L,
        mime_type = "text/plain",
        send_status = "SENDING",
        message_id = 5L,
      ).await()

      val row = db.messageQueries.getMessagesForDevice("device-1", 10).executeAsList().single()
      assertEquals(5L, row.message_id, "message_id must round-trip after repairing '${shape.label}'")
      assertEquals("SENDING", row.send_status, "send_status must round-trip after repairing '${shape.label}'")

      driver.close()
    }
  }
}
