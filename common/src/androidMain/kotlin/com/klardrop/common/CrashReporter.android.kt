package com.klardrop.common

import android.content.Context
import io.sentry.kotlin.multiplatform.Sentry

internal actual val crashReporterPlatform: String = "android"

/**
 * Android overload of [initCrashReporter]. The Android SDK needs an application
 * [Context], so it cannot share the common entry point.
 */
fun initCrashReporter(context: Context, appVersion: String, isProduction: Boolean) {
  if (!isProduction || CrashReporterConfig.DSN.isEmpty()) return
  Sentry.init(context) { options ->
    options.dsn = CrashReporterConfig.DSN
    options.release = appVersion
    options.environment = CrashReporterConfig.PRODUCTION_ENVIRONMENT
  }
}
