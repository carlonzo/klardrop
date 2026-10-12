package com.klardrop.common

import com.carlom.klardrop.common.KlardropVersion
import com.carlom.klardrop.common.utils.LogBuffer
import com.carlom.klardrop.common.utils.isExpectedNetworkNoise
import com.carlom.klardrop.common.utils.isKnownNoiseName
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.SentryOptions
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
   * Set once [initCrashReporter] actually calls `Sentry.init` (production build with a
   * compiled-in DSN). Every method below is a no-op until then: on Kotlin/Native, calling
   * into the Sentry KMP SDK before init reaches for a `Dispatchers.Main` that a headless
   * binary (e.g. `klardrop daemon`) never has, crashing the process instead of no-oping
   * the way the JVM/Android SDKs do.
   */
  internal var started: Boolean = false

  /**
   * Non-null on linuxX64: the curl-based Sentry envelope sender returned by
   * [platformCrashSender]. When set, all reporting methods use it instead of the
   * Sentry KMP SDK, whose linuxX64 klib is a no-op stub (see [platformCrashSender]).
   * User identity for it is kept here (not on the platform side) and passed as
   * arguments on each call — see [setUser].
   */
  internal var linuxSender: ((throwable: Throwable, level: String, userId: String, userName: String, osType: String) -> Unit)? = null

  /**
   * Non-null on linuxX64: the curl-based Sentry user-feedback sender returned by
   * [platformFeedbackSender], set alongside [linuxSender]. Used by [reportUserFeedback]
   * instead of the Sentry KMP SDK's message-event + UserFeedback path.
   */
  internal var linuxFeedbackSender: ((comments: String, name: String?, email: String?, userId: String, userName: String, osType: String) -> Boolean)? = null

  private var linuxUserId = ""
  private var linuxUserName = ""
  private var linuxOsType = ""


  /**
   * Reports [throwable] unless it is expected protocol noise (peer reset, connect
   * refused, BLE handshake disconnect). Call-site filtering keeps behaviour identical
   * across platforms; the `beforeSend` hook in [applyCrashReporterOptions] is the
   * backstop for native-SDK auto-capture, which never passes through here.
   *
   * [fatal] marks an exception that is about to take the process down with it (e.g. a
   * Kotlin/Native unhandled-exception hook, which runs right before `abort()`), so the
   * event is reported as Sentry level `fatal` instead of `error`.
   */
  fun notify(throwable: Throwable, fatal: Boolean = false) {
    if (!started) return
    if (throwable.isExpectedNetworkNoise()) return
    val sender = linuxSender
    if (sender != null) {
      sender(throwable, if (fatal) "fatal" else "error", linuxUserId, linuxUserName, linuxOsType)
    } else if (fatal) {
      Sentry.captureException(throwable) { scope -> scope.level = SentryLevel.FATAL }
    } else {
      Sentry.captureException(throwable)
    }
  }

  /**
   * Sends a user-authored problem report, and returns whether it actually went out.
   *
   * Sentry models user feedback as an annotation on an *existing event* rather than a standalone
   * submission, so this captures a message event first and attaches [comments] to it. That
   * indirection is the whole reason this is worth having: the event carries the current scope,
   * which means the last 100 breadcrumbs — every [com.carlom.klardrop.common.utils.log] call, see
   * `logger.kt` — ride along with the report. Breadcrumbs churn fast during reconnect storms, so
   * the report additionally carries the [LogBuffer] tail (up to [LOG_TAIL_LINES] recent lines) as
   * the `log_tail` extra — a wider window that survives the storm the user is reporting about.
   *
   * Every report groups under one Sentry issue (same message title) and carries `report:user`, so
   * they can be found without trawling crashes. [ReportOutcome.Disabled] is returned rather than
   * silently swallowed: local and pull-request builds have no DSN, and a UI that says "thanks,
   * sent!" to a report that went nowhere is worse than one that admits it.
   */
  fun reportUserFeedback(comments: String, name: String? = null, email: String? = null): ReportOutcome {
    if (!started) return ReportOutcome.Disabled
    // linuxX64: use the curl-based feedback sender instead of the Sentry KMP SDK, whose
    // linuxX64 klib is a no-op stub (see platformCrashSender). Sentry.isEnabled() must not
    // be called on this path either, since Sentry.init was never reached.
    if (linuxSender != null) {
      val fb = linuxFeedbackSender ?: return ReportOutcome.Disabled
      return if (fb(comments, name, email, linuxUserId, linuxUserName, linuxOsType)) {
        ReportOutcome.Sent
      } else {
        ReportOutcome.Failed
      }
    }
    if (!Sentry.isEnabled()) return ReportOutcome.Disabled
    val eventId = Sentry.captureMessage(USER_REPORT_TITLE) { scope ->
      scope.level = SentryLevel.INFO
      scope.setTag("report", "user")
      // ponytail: joined string, not structured lines — Sentry KMP setExtra only takes String.
      scope.setExtra(LOG_TAIL_EXTRA, LogBuffer.snapshot(LOG_TAIL_LINES).joinToString("\n"))
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
    if (!started) return
    // No-op for the linux sender: it has no scope to attach breadcrumbs to, and
    // carries the LogBuffer tail as the `log_tail` extra on the event instead.
    if (linuxSender != null) return
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
    if (!started) return
    if (linuxSender != null) {
      linuxUserId = deviceId
      linuxUserName = deviceName
      linuxOsType = osType
      return
    }
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
  private const val LOG_TAIL_EXTRA = "log_tail"
  private const val LOG_TAIL_LINES = 200
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
  initCrashReporter(appVersion, isProduction, CrashReporterConfig.DSN)
}

/** [dsn] overload for tests; production call sites always get [CrashReporterConfig.DSN]. */
internal fun initCrashReporter(appVersion: String, isProduction: Boolean, dsn: String) {
  if (!isProduction || dsn.isEmpty()) return
  val sender = platformCrashSender(dsn, appVersion)
  if (sender != null) {
    // linuxX64: bypass Sentry.init entirely (its klib is a no-op stub there) and use
    // the curl-based sender for every reporting method below.
    CrashReporter.linuxSender = sender
    CrashReporter.linuxFeedbackSender = platformFeedbackSender(dsn, appVersion)
  } else {
    Sentry.init { options ->
      applyCrashReporterOptions(options, appVersion)
    }
  }
  CrashReporter.started = true
}

/**
 * Shared SDK options for every platform entry point (common + Android).
 *
 * The `beforeSend` drop-filter re-applies the noise table to events the native SDKs
 * capture by themselves — uncaught coroutine cancellations landing on the thread's
 * uncaught-exception handler (Sentry KLARDROP-JW/JY: bare `JobCancellationException`
 * with no stacktrace) bypass `CrashReporter.notify` entirely, so call-site filtering
 * alone cannot stop them. Returning null drops the event. Message-only events (user
 * reports) carry no exceptions and always pass through.
 */
internal fun applyCrashReporterOptions(options: SentryOptions, appVersion: String) {
  options.dsn = CrashReporterConfig.DSN
  options.release = appVersion
  options.environment = CrashReporterConfig.environmentFor(appVersion)
  options.beforeSend = { event ->
    if (shouldDropEvent(event)) null else event
  }
}

/** Pure decision half of the `beforeSend` hook, so it stays unit-testable. */
internal fun shouldDropEvent(event: SentryEvent): Boolean {
  val exception = event.exceptions?.firstOrNull() ?: return false
  return isKnownNoiseName(exception.type.orEmpty(), exception.value.orEmpty())
}
