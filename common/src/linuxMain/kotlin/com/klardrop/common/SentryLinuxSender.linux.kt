@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package com.klardrop.common

import com.carlom.klardrop.common.utils.LogBuffer
import com.carlom.klardrop.common.utils.execProcess
import kotlin.concurrent.AtomicReference
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr

// ponytail: per-process event cap via a copy-and-swap dedupe set (<=EVENT_CAP entries),
// so a reconnect storm can't spawn hundreds of curl invocations. Exact (type+message)
// duplicates are suppressed too. A real rate limiter would live server-side in Sentry.
private const val EVENT_CAP = 20
private val seenKeys = AtomicReference<Set<String>>(emptySet())

/** Resets mutable state; only call from tests. */
internal fun resetLinuxSenderStateForTest() {
  seenKeys.value = emptySet()
}

// ---------------------------------------------------------------------------
// DSN parsing
// ---------------------------------------------------------------------------

internal data class ParsedDsn(
  val scheme: String,
  val originalDsn: String, // as given, for the envelope header's "dsn" field
  val envelopeUrl: String,
  val authHeader: String,
)

/**
 * Parses a Sentry DSN of the form `scheme://publicKey@host[:port]/projectId`.
 * Returns null and logs the reason (never the DSN itself, which carries the secret key)
 * on any parse failure, so the daemon starts cleanly even if the DSN is malformed.
 */
internal fun parseDsn(dsn: String, appVersion: String): ParsedDsn? {
  val schemeEnd = dsn.indexOf("://")
  if (schemeEnd < 0) return dsnParseFailed("no scheme")
  val scheme = dsn.substring(0, schemeEnd)

  val rest = dsn.substring(schemeEnd + 3)
  val atIdx = rest.indexOf('@')
  if (atIdx < 0) return dsnParseFailed("no @")
  val publicKey = rest.substring(0, atIdx)
  if (publicKey.isEmpty()) return dsnParseFailed("empty key")

  val hostAndPath = rest.substring(atIdx + 1)
  val slashIdx = hostAndPath.indexOf('/')
  if (slashIdx < 0) return dsnParseFailed("no project path")
  val host = hostAndPath.substring(0, slashIdx)
  val projectId = hostAndPath.substring(slashIdx + 1).trimEnd('/')
  if (host.isEmpty() || projectId.isEmpty()) return dsnParseFailed("empty host or project id")

  val envelopeUrl = "$scheme://$host/api/$projectId/envelope/"
  val authHeader =
    "Sentry sentry_version=7, sentry_key=$publicKey, sentry_client=klardrop-linux/$appVersion"

  return ParsedDsn(scheme, dsn, envelopeUrl, authHeader)
}

private fun dsnParseFailed(reason: String): ParsedDsn? {
  fprintf(stderr, "[SentryLinuxSender]: invalid DSN (%s)\n", reason)
  fflush(stderr)
  return null
}

// ---------------------------------------------------------------------------
// Envelope builder
// ---------------------------------------------------------------------------

/**
 * Builds a Sentry envelope (3 newline-terminated JSON lines) for [throwable]. JSON is built
 * with kotlinx.serialization so string escaping (control chars, quotes) is correct for
 * anything that ends up in it — exception messages and log lines are not our text.
 *
 * The envelope format is documented at https://develop.sentry.dev/sdk/envelopes/
 */
internal fun buildSentryEnvelope(
  parsedDsn: ParsedDsn,
  throwable: Throwable,
  level: String,
  appVersion: String,
  userId: String,
  userName: String,
  osType: String,
): ByteArray {
  val eventId = Uuid.random().toHexString()
  val timestamp = Clock.System.now().toString()
  val environment = CrashReporterConfig.environmentFor(appVersion)

  val exType = throwable::class.simpleName ?: throwable::class.qualifiedName ?: "Throwable"
  val exMsg = throwable.message.orEmpty()

  // Log tail carried as extra for context width beyond breadcrumbs.
  val logTail = LogBuffer.snapshot(200).joinToString("\n")

  val header = buildJsonObject {
    put("event_id", eventId)
    put("dsn", parsedDsn.originalDsn)
    put("sent_at", timestamp)
  }.toString()

  val itemHeader = buildJsonObject {
    put("type", "event")
    put("content_type", "application/json")
  }.toString()

  val event = buildJsonObject {
    put("event_id", eventId)
    put("timestamp", timestamp)
    // "other": this envelope doesn't carry the debug_meta/images a real native SDK would,
    // so it shouldn't claim the "native" platform's symbolication contract.
    put("platform", "other")
    put("level", level)
    put("release", appVersion)
    put("environment", environment)
    putJsonObject("tags") {
      put("device.platform", "linux")
      put("device.osType", osType)
    }
    putJsonObject("user") {
      put("id", userId)
      put("username", userName)
    }
    putJsonObject("exception") {
      putJsonArray("values") {
        addJsonObject {
          put("type", exType)
          put("value", exMsg)
        }
      }
    }
    putJsonObject("extra") {
      put("log_tail", logTail)
      // Raw Kotlin/Native trace, not parsed into Sentry frames: K/N's `kfun:...+123
      // (/path/File.kt:42:13)` format doesn't match any Sentry frame convention, so a
      // best-effort parser would just mis-render — the raw text is more useful as-is.
      put("stacktrace", throwable.stackTraceToString())
    }
  }.toString()

  return "$header\n$itemHeader\n$event\n".encodeToByteArray()
}

/**
 * Builds a Sentry envelope (5 newline-terminated JSON lines: header, event item header,
 * event, user_report item header, user_report payload) for a user-authored problem report.
 * Mirrors the SDK path in [CrashReporter.reportUserFeedback]: a message event ("User report")
 * that a [io.sentry.kotlin.multiplatform.protocol.UserFeedback]-shaped `user_report` item
 * attaches to via the shared event id. Never applies the crash [EVENT_CAP]/dedupe gate —
 * every user report is intentional and should go out.
 */
internal fun buildSentryFeedbackEnvelope(
  parsedDsn: ParsedDsn,
  comments: String,
  name: String?,
  email: String?,
  appVersion: String,
  userId: String,
  userName: String,
  osType: String,
): ByteArray {
  val eventId = Uuid.random().toHexString()
  val timestamp = Clock.System.now().toString()
  val environment = CrashReporterConfig.environmentFor(appVersion)
  val logTail = LogBuffer.snapshot(200).joinToString("\n")

  val header = buildJsonObject {
    put("event_id", eventId)
    put("dsn", parsedDsn.originalDsn)
    put("sent_at", timestamp)
  }.toString()

  val eventItemHeader = buildJsonObject {
    put("type", "event")
    put("content_type", "application/json")
  }.toString()

  val event = buildJsonObject {
    put("event_id", eventId)
    put("timestamp", timestamp)
    put("platform", "other")
    put("level", "info")
    put("release", appVersion)
    put("environment", environment)
    putJsonObject("message") {
      put("formatted", "User report")
    }
    putJsonObject("tags") {
      put("report", "user")
      put("device.platform", "linux")
      put("device.osType", osType)
    }
    putJsonObject("user") {
      put("id", userId)
      put("username", userName)
    }
    putJsonObject("extra") {
      put("log_tail", logTail)
    }
  }.toString()

  val userReportItemHeader = buildJsonObject {
    put("type", "user_report")
  }.toString()

  val userReport = buildJsonObject {
    put("event_id", eventId)
    put("name", name.orEmpty())
    put("email", email.orEmpty())
    put("comments", comments)
  }.toString()

  return "$header\n$eventItemHeader\n$event\n$userReportItemHeader\n$userReport\n".encodeToByteArray()
}

// ---------------------------------------------------------------------------
// curl transport, shared by the crash and feedback senders
// ---------------------------------------------------------------------------

/** Sends [envelope] to [parsedDsn]'s ingest endpoint via curl; returns true iff curl exits 0. */
private fun sendEnvelopeViaCurl(parsedDsn: ParsedDsn, envelope: ByteArray): Boolean {
  val argv = listOf(
    "curl", "-fsS", "--max-time", "10",
    "--proto", "=${parsedDsn.scheme}",
    "-H", "Content-Type: application/x-sentry-envelope",
    "-H", "X-Sentry-Auth: ${parsedDsn.authHeader}",
    "--data-binary", "@-",
    parsedDsn.envelopeUrl,
  )
  val result = execProcess(argv, stdin = envelope, timeoutMillis = 12_000L)
  if (result.exitCode != 0) {
    fprintf(stderr, "[SentryLinuxSender]: curl exit %d: %s\n", result.exitCode, result.stderrString)
    fflush(stderr)
  }
  return result.exitCode == 0
}

internal actual fun platformFeedbackSender(
  dsn: String,
  appVersion: String,
): ((comments: String, name: String?, email: String?, userId: String, userName: String, osType: String) -> Boolean)? {
  val parsedDsn = parseDsn(dsn, appVersion) ?: return null

  return { comments, name, email, userId, userName, osType ->
    runCatching {
      val envelope = buildSentryFeedbackEnvelope(
        parsedDsn = parsedDsn,
        comments = comments,
        name = name,
        email = email,
        appVersion = appVersion,
        userId = userId,
        userName = userName,
        osType = osType,
      )
      sendEnvelopeViaCurl(parsedDsn, envelope)
    }.onFailure { ex ->
      fprintf(stderr, "[SentryLinuxSender]: feedback send failed: %s\n", ex.message ?: ex.toString())
      fflush(stderr)
    }.getOrDefault(false)
  }
}

// ---------------------------------------------------------------------------
// Main entry point
// ---------------------------------------------------------------------------

/** Cap + dedupe gate; the noise filter itself already ran in `CrashReporter.notify`. */
internal fun shouldSendEvent(exType: String, exMsg: String): Boolean {
  val key = "$exType:$exMsg"
  while (true) {
    val current = seenKeys.value
    if (key in current || current.size >= EVENT_CAP) return false
    if (seenKeys.compareAndSet(current, current + key)) return true
  }
}

internal actual fun platformCrashSender(
  dsn: String,
  appVersion: String,
): ((throwable: Throwable, level: String, userId: String, userName: String, osType: String) -> Unit)? {
  val parsedDsn = parseDsn(dsn, appVersion) ?: return null

  return { throwable, level, userId, userName, osType ->
    runCatching {
      val exType = throwable::class.simpleName ?: throwable::class.qualifiedName ?: ""
      val exMsg = throwable.message.orEmpty()
      if (!shouldSendEvent(exType, exMsg)) return@runCatching

      val envelope = buildSentryEnvelope(
        parsedDsn = parsedDsn,
        throwable = throwable,
        level = level,
        appVersion = appVersion,
        userId = userId,
        userName = userName,
        osType = osType,
      )

      sendEnvelopeViaCurl(parsedDsn, envelope)
    }.onFailure { ex ->
      fprintf(stderr, "[SentryLinuxSender]: send failed: %s\n", ex.message ?: ex.toString())
      fflush(stderr)
    }
  }
}
