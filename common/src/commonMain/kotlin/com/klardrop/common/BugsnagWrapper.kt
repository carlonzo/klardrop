package com.klardrop.common

expect object BugsnagWrapper {
  fun notify(throwable: Throwable)
  fun leaveBreadcrumb(message: String, type: BugsnagBreadcrumbType)
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