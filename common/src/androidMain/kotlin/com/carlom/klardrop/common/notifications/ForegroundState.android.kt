package com.carlom.klardrop.common.notifications

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counts started Activities to determine whether the app is foregrounded.
 * Lighter than pulling in the full lifecycle-process dependency just to read
 * a single boolean.
 *
 * Registered via [Application.registerActivityLifecycleCallbacks] in the
 * monitor's constructor; the count goes up on `onStart` and down on `onStop`,
 * with `>0` meaning at least one Activity is in the started state and the
 * user is plausibly looking at the app.
 */
actual class ForegroundState(context: Context) {

  private val flow = MutableStateFlow(false)
  actual val isForeground: StateFlow<Boolean> = flow.asStateFlow()

  init {
    val app = context.applicationContext as? Application
    if (app == null) {
      log("ForegroundState", "Context is not an Application; defaulting to foreground=false")
    } else {
      var startedCount = 0
      app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) {
          startedCount++
          if (startedCount == 1) flow.value = true
        }
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) {
          startedCount = (startedCount - 1).coerceAtLeast(0)
          if (startedCount == 0) flow.value = false
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
      })
    }
  }
}
