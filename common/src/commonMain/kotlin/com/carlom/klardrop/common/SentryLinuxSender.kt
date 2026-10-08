package com.klardrop.common

/**
 * Returns a platform-specific Sentry envelope sender for the native Linux target, or
 * null on every other target (including the JVM desktop and Apple targets that use the
 * real Sentry SDK). The returned function must not throw.
 *
 * Receiving null here means the regular [Sentry.init] path is used instead. User identity
 * (id/name/osType) is passed as arguments on each call rather than cached on the platform
 * side, so it lives in one place: [CrashReporter.setUser].
 */
internal expect fun platformCrashSender(
  dsn: String,
  appVersion: String,
): ((throwable: Throwable, level: String, userId: String, userName: String, osType: String) -> Unit)?

/**
 * Returns a platform-specific Sentry user-feedback sender for the native Linux target, or
 * null on every other target (including the JVM desktop and Apple targets that use the
 * real Sentry SDK). The returned function must not throw and returns whether the send
 * succeeded (curl exit 0).
 */
internal expect fun platformFeedbackSender(
  dsn: String,
  appVersion: String,
): ((comments: String, name: String?, email: String?, userId: String, userName: String, osType: String) -> Boolean)?
