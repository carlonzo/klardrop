package com.klardrop.common

expect object BugsnagWrapper {
  fun notify(throwable: Throwable)
  fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType)

  /**
   * Tags subsequent events/crashes with the running platform + device identity so
   * the shared Bugsnag project can be filtered by platform and a crash tied to a
   * device. The native SDKs already capture OS/model; this adds our own
   * `device.platform` (compile-time target) and `device.osType` (runtime).
   */
  fun setUser(deviceId: String, deviceName: String, osType: String)
}

enum class BugsnagBreadcrumbType {
  ERROR,
  LOG,
  MANUAL,
  NAVIGATION,
  PROCESS,
  REQUEST,
  STATE,
  USER,
}

object BugsnagConfig {
  val apiKey = "3e6d40359747c7552a4dd9bdd45ddf16"
}