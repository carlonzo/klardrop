package com.carlom.klardrop.common.utils

//import io.sentry.kotlin.multiplatform.Sentry

actual fun initSentryPlatform() {

//  Thread.setDefaultUncaughtExceptionHandler { _, e: Throwable ->
//    Sentry.captureException(e)
//    throw e
//  }

}

actual val sentryPlatformName: String = "android"