package com.carlom.klardrop.android

import android.app.Application
import android.content.Context
import com.bugsnag.android.Bugsnag
import com.bugsnag.android.Configuration
import com.carlom.klardrop.android.di.ApplicationComponent
import com.carlom.klardrop.android.di.DaggerApplicationComponent
import com.carlom.klardrop.common.ApplicationInfo
import com.klardrop.common.BugsnagConfig

class KlarDropApplication : Application(), ApplicationComponentProvider {

  private lateinit var component: ApplicationComponent
  override val applicationComponent: ApplicationComponent
    get() = component

  override fun onCreate() {
    super.onCreate()

    Bugsnag.start(
      this,
      Configuration(BugsnagConfig.apiKey)
    )

    val applicationInfo = ApplicationInfo()

    component = DaggerApplicationComponent.factory().create(this, applicationInfo)
    component.klardrop().init()
  }

}

interface ApplicationComponentProvider {
  val applicationComponent: ApplicationComponent
}

fun Context.applicationComponent(): ApplicationComponent {
  return (applicationContext as ApplicationComponentProvider).applicationComponent
}