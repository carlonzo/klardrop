package com.klardrop.common

import com.carlom.klardrop.common.KlardropVersion
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.SentryId
import io.sentry.kotlin.multiplatform.protocol.User
import io.sentry.kotlin.multiplatform.protocol.UserFeedback

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

  /**
   * Sends a user-authored problem report, and returns whether it actually went out.
   *
   * Sentry models user feedback as an annotation on an *existing event* rather than a standalone
   * submission, so this captures a message event first and attaches [comments] to it. That
   * indirection is the whole reason this is worth having: the event carries the current scope,
   * which means the last 100 breadcrumbs — every [com.carlom.klardrop.common.utils.log] call, see
   * `logger.kt` — ride along with the report. A user hitting a connection problem then produces
   * something debuggable, instead of the "it wouldn't connect" that a plain feedback form gives.
   *
   * Every report groups under one Sentry issue (same message title) and carries `report:user`, so
   * they can be found without trawling crashes. [ReportOutcome.Disabled] is returned rather than
   * silently swallowed: local and pull-request builds have no DSN, and a UI that says "thanks,
   * sent!" to a report that went nowhere is worse than one that admits it.
   */
  fun reportUserFeedback(comments: String, name: String? = null, email: String? = null): ReportOutcome {
    if (!Sentry.isEnabled()) return ReportOutcome.Disabled
    val eventId = Sentry.captureMessage(USER_REPORT_TITLE) { scope ->
      scope.level = SentryLevel.INFO
      scope.setTag("report", "user")
    }
    // A dropped event (sampling, an inbound filter, rate limit) yields the nil id, and feedback
    // attached to it would be unreachable — say it failed rather than pretend otherwise.
    if (eventId == SentryId.EMPTY_ID) return ReportOutcome.Failed
    Sentry.captureUserFeedback(
      UserFeedback(eventId).apply {
        this.comments = comments
        name?.takeIf { it.isNotBlank() }?.let { this.name = it }
        email?.takeIf { it.isNotBlank() }?.let { this.email = it }
      }
    )
    return ReportOutcome.Sent
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

  private const val USER_REPORT_TITLE = "User report"
}

/** Result of [CrashReporter.reportUserFeedback], so the UI can tell the user the truth. */
enum class ReportOutcome {
  Sent,

  /** No DSN was compiled in (local or pull-request build), or the SDK never started. */
  Disabled,

  /** The SDK is running but dropped the event, so there is nothing to attach the report to. */
  Failed,
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
   * Sentry DSN, baked in at compile time from the `klardropSentryDsn` Gradle property
   * (see `common/build.gradle.kts`). Deliberately not checked in: this repository is
   * public and a DSN is a write-only ingest endpoint, so a committed one is free quota
   * for anyone who scrapes GitHub. It is still recoverable from a shipped binary, so
   * Sentry-side rate limits and inbound filters remain the real backstop.
   *
   * Empty in local and pull-request builds, which [initCrashReporter] treats as
   * "crash reporting disabled".
   */
  val DSN: String = KlardropVersion.SENTRY_DSN

  /**
   * The Sentry `environment` for [appVersion].
   *
   * This has to be derived from the version rather than hard-coded, because nightlies ship to
   * real testers (TestFlight, Play `beta`, the rolling prerelease) and their crashes must be
   * separable from production ones. `sentry-cli deploys new -e nightly` does NOT do that: a
   * deploy only records "this release reached environment X" — issue filters and
   * regression detection read the *event's* environment field, which is this one. Tagging
   * every nightly `production` would have quietly made "is this crash only on the tester
   * track?" unanswerable.
   *
   * Keyed off the pre-release suffix the nightly pipeline already puts in the version
   * (1.0.1-nightly.N vs 1.0.1) so it needs no extra Gradle property plumbed through four
   * jobs — and, unlike a property, it cannot drift out of step with the release name.
   */
  fun environmentFor(appVersion: String): String =
    if (appVersion.contains("-nightly.")) "nightly" else "production"
}

/** Compile-time target name, reported as the `device.platform` tag. */
internal expect val crashReporterPlatform: String

/**
 * Starts the SDK for every target except Android, which needs an application `Context`
 * and so has its own overload in `androidMain`. Safe to call from Apple and desktop JVM
 * entry points.
 *
 * A no-op unless this is a production build *and* a DSN was injected at compile time.
 * The DSN check is the load-bearing one: only the release workflows pass
 * `klardropSentryDsn`, so a locally built or pull-request binary physically cannot
 * report, regardless of what [isProduction] says.
 */
fun initCrashReporter(appVersion: String, isProduction: Boolean) {
  if (!isProduction || CrashReporterConfig.DSN.isEmpty()) return
  Sentry.init { options ->
    options.dsn = CrashReporterConfig.DSN
    options.release = appVersion
    options.environment = CrashReporterConfig.environmentFor(appVersion)
  }
}
