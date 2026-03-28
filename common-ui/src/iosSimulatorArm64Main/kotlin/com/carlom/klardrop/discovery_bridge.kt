@file:Suppress("unused")

package com.carlom.klardrop

import androidx.compose.ui.window.ComposeUIViewController
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme

class DiscoveryBridge {
  val klardrop = Klardrop(internalPlatformDependency = InternalPlatformDependencies(ApplicationInfo()))

  init {
    klardrop.init()
  }

  fun RootKlardropApp() = ComposeUIViewController {


    AppTheme {
      KlardropApp(
        klardrop = klardrop,
      )
    }

  }
}
