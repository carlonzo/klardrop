package com.klardrop.common

// Android uses the real Sentry SDK via Sentry.init; no custom sender needed.
internal actual fun platformCrashSender(
  dsn: String,
  appVersion: String,
): ((throwable: Throwable, level: String, userId: String, userName: String, osType: String) -> Unit)? = null

internal actual fun platformFeedbackSender(
  dsn: String,
  appVersion: String,
): ((comments: String, name: String?, email: String?, userId: String, userName: String, osType: String) -> Boolean)? = null
