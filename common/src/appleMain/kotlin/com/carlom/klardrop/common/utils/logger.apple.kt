package com.carlom.klardrop.common.utils

import platform.Foundation.NSLog

// The message is the format string, so escape '%' (else it's read as a specifier). Don't use
// NSLog("%@", kotlinString): K/N doesn't bridge a String passed as a variadic arg to NSString,
// and NSLog crashes (EXC_BREAKPOINT in objc_opt_respondsToSelector) on the first log line.
private fun nslog(line: String) = NSLog(line.replace("%", "%%"))

internal actual fun nativeLogger(tag: String, message: String) {
  nslog("[Klardrop]: [$tag]: $message")
}

internal actual fun nativeLoggerException(tag: String, message: String, throwable: Throwable) {
  nslog("[Klardrop]: ERROR [$tag]: $message")
  nslog("[Klardrop]: ERROR [$tag]: ${throwable.stackTraceToString()}")
}
