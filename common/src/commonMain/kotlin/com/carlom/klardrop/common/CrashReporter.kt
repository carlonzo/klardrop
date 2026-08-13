package com.klardrop.common

import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.User

/**
 * Crash/error reporting, backed by the Sentry KMP SDK.
 *
 * This used to be `expect object BugsnagWrapper` with four `actual`s, because
 * Bugsnag ships no macOS KMP artifact — macOS had to reach the Cocoa SDK through the
 * CocoaPods-generated `cocoapods.Bugsnag` cinterop, which made the module's *source*
 * depend on the CocoaPods integration. Sentry publishes real `macosArm64`/`macosX64`
 * artifacts, so every target now shares one common implementation and nothing in
 * Kotlin imports `cocoapods.*`.
 *
 * Reporting is common; *initialization* stays at the platform entry points, because
 * the Android SDK needs an application [android.content.Context]. See
 * [initCrashReporter] in each source set.
 */
object CrashReporter {

  /**
   * Reports [throwable] unless it is expected protocol noise (peer reset, connect
   * refused, BLE handshake disconnect). Filtering here rather than in a `beforeSend`
   * hook keeps the behaviour identical across platforms and matches what the Bugsnag
   * wrappers did.
   */
  fun notify(throwable: Throwable) {
    if (throwable.isExpectedNetworkNoise()) return
    Sentry.captureException(throwable)
  }

  fun leaveBreadcrumb(message: String, type: BreadcrumbType = BreadcrumbType.MANUAL) {
    Sentry.addBreadcrumb(
      Breadcrumb().apply {
        this.message = message
        this.category = type.category
        this.level = type.level
      }
    )
  }

  /**
   * Tags subsequent events with the running platform + device identity so the shared
   * Sentry project can be filtered by platform and a crash tied to a device. The native
   * SDKs already capture OS/model; this adds our own `device.platform` (compile-time
   * target) and `device.osType` (runtime).
   */
  fun setUser(deviceId: String, deviceName: String, osType: String) {
    Sentry.setUser(
      User().apply {
        id = deviceId
        username = deviceName
      }
    )
    Sentry.configureScope { scope ->
      scope.setTag("device.platform", crashReporterPlatform)
      scope.setTag("device.osType", osType)
    }
  }
}

/**
 * Breadcrumb classification. Bugsnag had a first-class breadcrumb *type*; Sentry models
 * the same thing as a free-form `category` plus a level, so each entry carries both and
 * the call sites in `logger.kt` stay unchanged.
 */
enum class BreadcrumbType(
  internal val category: String,
  internal val level: SentryLevel,
) {
  ERROR("error", SentryLevel.ERROR),
  LOG("log", SentryLevel.INFO),
  MANUAL("manual", SentryLevel.INFO),
  NAVIGATION("navigation", SentryLevel.INFO),
  PROCESS("process", SentryLevel.INFO),
  REQUEST("request", SentryLevel.INFO),
  STATE("state", SentryLevel.INFO),
  USER("user", SentryLevel.INFO),
}

object CrashReporterConfig {
  /**
   * Sentry DSN. Unlike the Bugsnag API key this replaces, a DSN is not a secret — it is
   * a write-only ingest endpoint and is expected to ship in the client.
   */
  const val DSN = "https://examplePublicKey@o0.ingest.sentry.io/0"

  /**
   * Only production builds report. Development churn (debug builds, hot reload, manual
   * disconnect tests) was filling the dashboard with peer-hangup noise that masked real
   * production issues — this is the Sentry equivalent of Bugsnag's
   * `enabledReleaseStages = setOf("production")`.
   */
  const val PRODUCTION_ENVIRONMENT = "production"
}

/** Compile-time target name, reported as the `device.platform` tag. */
internal expect val crashReporterPlatform: String

/**
 * Starts the SDK for every target except Android, which needs an application `Context`
 * and so has its own overload in `androidMain`. Safe to call from Apple and desktop JVM
 * entry points.
 */
fun initCrashReporter(appVersion: String, isProduction: Boolean) {
  if (!isProduction) return
  Sentry.init { options ->
    options.dsn = CrashReporterConfig.DSN
    options.release = appVersion
    options.environment = CrashReporterConfig.PRODUCTION_ENVIRONMENT
  }
}
