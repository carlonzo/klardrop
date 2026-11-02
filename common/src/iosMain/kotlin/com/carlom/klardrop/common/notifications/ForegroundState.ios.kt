package com.carlom.klardrop.common.notifications

import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObject

public actual class ForegroundState {

  private val flow = MutableStateFlow(false)
  public actual val isForeground: StateFlow<Boolean> = flow.asStateFlow()

  init {
    // applicationState is main-thread-only, and this can be built lazily from an IO coroutine.
    val readState = { flow.value = UIApplication.sharedApplication.applicationState.value == 0L }
    if (NSThread.isMainThread) readState() else NSOperationQueue.mainQueue.addOperationWithBlock(readState)

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
