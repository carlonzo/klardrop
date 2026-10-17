package com.carlom.klardrop.common.notifications

import android.app.Activity
import android.app.Application
import android.content.Context
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * KLARDROP-KA: on a cold start MainActivity can reach onStart before anything reads
 * foregroundState(). If ForegroundState only registered its lifecycle callbacks on that first
 * read, the start was missed, isForeground stayed false and discovery stayed OFF for the session.
 */
class ForegroundStateRegistrationTest {

  private class FakeApplication : Application() {
    val callbacks = mutableListOf<ActivityLifecycleCallbacks>()
    override fun getApplicationContext(): Context = this
    override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) {
      callbacks += callback
    }
  }

  @Test
  fun activityStartedBeforeFirstReadStillCountsAsForeground() {
    val app = FakeApplication()
    val deps = InternalPlatformDependencies(app, ApplicationInfo())

    // The Activity starts before anyone has asked for foregroundState().
    app.callbacks.forEach { it.onActivityStarted(Activity()) }

    assertTrue(deps.foregroundState().isForeground.value)
  }
}
