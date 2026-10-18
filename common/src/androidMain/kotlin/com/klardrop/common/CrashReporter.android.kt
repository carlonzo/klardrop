package com.klardrop.common

import android.content.Context
import io.sentry.kotlin.multiplatform.Sentry

internal actual val crashReporterPlatform: String = "android"

/**
 * Android overload of [initCrashReporter]. The Android SDK needs an application
 * [Context], so it cannot share the common entry point.
 */
public fun initCrashReporter(context: Context, appVersion: String, isProduction: Boolean): Unit {
  if (!isProduction || CrashReporterConfig.DSN.isEmpty()) return
  Sentry.init(context) { options ->
    applyCrashReporterOptions(options, appVersion)
  }
  CrashReporter.started = true
}
