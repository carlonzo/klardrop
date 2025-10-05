package com.carlom.klardrop.android.di

import android.content.Context
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class KlardropModule {

  @Provides
  fun providesInternalPlatformDependency(context: Context, applicationInfo: ApplicationInfo): InternalPlatformDependencies {
    return InternalPlatformDependencies(context, applicationInfo)
  }

  @Singleton
  @Provides
  fun providesKlardrop(internalPlatformDependencies: InternalPlatformDependencies): Klardrop {
    return Klardrop(internalPlatformDependency = internalPlatformDependencies)
  }

}