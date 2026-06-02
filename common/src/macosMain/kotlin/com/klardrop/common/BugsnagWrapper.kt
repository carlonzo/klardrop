package com.klardrop.common

// macOS v1 ships without Bugsnag (the Bugsnag pod/kmp macOS support is uncertain).
// No-op so the common crash-reporting surface compiles and links on macOS.
actual object BugsnagWrapper {
  actual fun notify(throwable: Throwable) = Unit
  actual fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType) = Unit
}
