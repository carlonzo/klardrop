package com.carlom.klardrop.common.utils

/**
 * No-op: on the JVM an uncaught coroutine exception goes to the thread's uncaught handler, which
 * logs (and, with Bugsnag installed, reports) without killing the process.
 */
actual fun installUnhandledExceptionGuard() = Unit
