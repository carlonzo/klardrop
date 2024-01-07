package com.carlom.klardrop.common.persistence

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory {
  fun createDriver(): SqlDriver
}

internal const val DB_NAME = "klardrop.db"
