package com.carlom.klardrop.common.database

import app.cash.sqldelight.db.SqlDriver

// For desktopJvm tests
expect fun createTestDriver(): SqlDriver

// You might need expect/actual for test DriverFactory if you plan to run these tests on non-JVM platforms.
// For now, focusing on JVM tests.
