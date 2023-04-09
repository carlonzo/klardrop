package com.carlom.klardrop.android

import android.app.Application
import android.content.Context
import com.carlom.klardrop.android.di.ApplicationComponent
import com.carlom.klardrop.android.di.DaggerApplicationComponent

class KlarDropApplication : Application(), ApplicationComponentProvider {

  private lateinit var compoenent: ApplicationComponent
  override val applicationComponent: ApplicationComponent
    get() = compoenent

  override fun onCreate() {
    super.onCreate()

    compoenent = DaggerApplicationComponent.factory().create(this)

    compoenent.klardrop().init()
  }


}

interface ApplicationComponentProvider {
  val applicationComponent: ApplicationComponent
}

fun Context.applicationComponent(): ApplicationComponent {
  return (applicationContext as ApplicationComponentProvider).applicationComponent
}