package com.carlom.klardrop.common.notifications

import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationStateActive
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject

actual class ForegroundState {

  private val flow = MutableStateFlow(false)
  actual val isForeground: StateFlow<Boolean> = flow.asStateFlow()

  init {
    flow.value = UIApplication.sharedApplication.applicationState == UIApplicationStateActive

    val center = NSNotificationCenter.defaultCenter
    val queue = NSOperationQueue.mainQueue
    center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, queue) { _ ->
      flow.value = true
    }
    center.addObserverForName(UIApplicationWillResignActiveNotification, null, queue) { _ ->
      flow.value = false
    }
  }
}
