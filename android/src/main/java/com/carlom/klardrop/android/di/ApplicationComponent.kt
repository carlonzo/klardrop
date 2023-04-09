package com.carlom.klardrop.android.di

import com.carlom.klardrop.android.MainActivity
import com.carlom.klardrop.android.ShareToDeviceActivity
import com.carlom.klardrop.common.Klardrop
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [KlardropModule::class])
interface ApplicationComponent {

  fun klardrop(): Klardrop
  fun inject(mainActivity: MainActivity)
  fun inject(mainActivity: ShareToDeviceActivity)

  @Component.Factory
  interface Factory {
    fun create(@BindsInstance context: android.content.Context): ApplicationComponent
  }

}