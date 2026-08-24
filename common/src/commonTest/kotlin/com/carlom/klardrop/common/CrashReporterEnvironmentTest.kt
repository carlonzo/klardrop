package com.carlom.klardrop.common

import com.klardrop.common.CrashReporter
import com.klardrop.common.CrashReporterConfig
import com.klardrop.common.ReportOutcome
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

  /**
   * No DSN is compiled into a local or pull-request build, so the SDK never starts and a problem
   * report has nowhere to go. The report UI reads this outcome to say so — the failure mode being
   * guarded is a form that thanks the user for a report that silently evaporated. Also pins that
   * the call is safe with no SDK running rather than throwing from a UI callback.
   */
  @Test
  fun userFeedbackReportsDisabledWhenTheSdkIsNotRunning() {
    assertEquals(ReportOutcome.Disabled, CrashReporter.reportUserFeedback("cannot connect to my laptop"))
  }
}
