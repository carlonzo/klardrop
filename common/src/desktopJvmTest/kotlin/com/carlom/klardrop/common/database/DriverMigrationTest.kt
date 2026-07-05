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
 * would open successfully at the same schema version and then crash on the first `insert` /
 * `updateSendStatusByMessageId` with "table messages has no column named message_id".
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
}
