package com.carlom.klardrop.common.database

import android.app.Application

actual fun createTestDriver(): app.cash.sqldelight.db.SqlDriver {
  val app = ApplicationProvider.getApplicationContext<Application>()
  return AndroidSqliteDriver(LibraryDB.Schema, app, null)
}