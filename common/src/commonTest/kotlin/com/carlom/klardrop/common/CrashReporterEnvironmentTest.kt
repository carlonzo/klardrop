package com.carlom.klardrop.common

import com.klardrop.common.CrashReporterConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nightlies ship to real testers, so their crashes have to be separable from production ones
 * in Sentry. That separation lives in the *event's* environment field — a `deploys new -e
 * nightly` record does not affect issue filtering — and the field is derived from the version
 * string the release pipelines bake in. Pinned here because the failure is silent: every
 * tester crash would simply arrive labelled `production`.
 */
class CrashReporterEnvironmentTest {

  @Test
  fun nightlyVersionsReportAsNightly() {
    assertEquals("nightly", CrashReporterConfig.environmentFor("1.0.1-nightly.42"))
  }

  @Test
  fun stableVersionsReportAsProduction() {
    assertEquals("production", CrashReporterConfig.environmentFor("1.0.1"))
  }
}
