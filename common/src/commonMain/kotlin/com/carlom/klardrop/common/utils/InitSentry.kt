package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.ApplicationInfo
//import io.sentry.kotlin.multiplatform.Sentry

object InitSentry {

  fun init(applicationInfo: ApplicationInfo) {
//    Sentry.init {
//      it.dsn = "https://d62d323a646cef70912775e4719f53a1@o4507827226148864.ingest.de.sentry.io/4507827320520784"
//      it.debug = applicationInfo.isDebug
//    }

    initSentryPlatform()

//    Sentry.configureScope {
//      it.setContext("platform", sentryPlatformName)
//    }
  }

}

expect fun initSentryPlatform()
expect val sentryPlatformName: String