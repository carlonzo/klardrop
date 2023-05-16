package com.carlom.klardrop.android.di

import android.content.Context
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class KlardropModule {

  @Provides
  fun providesInternalPlatformDependency(context: Context): InternalPlatformDependencies {
    return InternalPlatformDependencies(context)
  }

  @Singleton
  @Provides
  fun providesKlardrop(internalPlatformDependencies: InternalPlatformDependencies): Klardrop {
    return Klardrop(internalPlatformDependency = internalPlatformDependencies)
  }

}