package com.carlom.klardrop.android

import android.app.Application
import android.content.Context
import com.carlom.klardrop.android.di.ApplicationComponent
import com.carlom.klardrop.android.di.DaggerApplicationComponent
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

class KlarDropApplication : Application(), ApplicationComponentProvider {

  private lateinit var component: ApplicationComponent
  override val applicationComponent: ApplicationComponent
    get() = component

  override fun onCreate() {
    super.onCreate()

    component = DaggerApplicationComponent.factory().create(this)
    component.klardrop().init()
  }

}

interface ApplicationComponentProvider {
  val applicationComponent: ApplicationComponent
}

fun Context.applicationComponent(): ApplicationComponent {
  return (applicationContext as ApplicationComponentProvider).applicationComponent
}