@file:Suppress("unused")

package com.carlom.klardrop

import androidx.compose.ui.window.ComposeUIViewController
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme

class DiscoveryBridge {

  val klardrop = Klardrop(internalPlatformDependency = InternalPlatformDependencies())

  init {
    klardrop.init()
  }

  fun RootKlardropApp() = ComposeUIViewController {

    val uiDependencies = object : UiDependencies {
      override fun filePickerFactory(): FilePickerFactory {
        return FilePickerFactory(klardrop.commonComponent.platformFileSystem())
      }

    }

    AppTheme {
      KlardropApp(
        klardrop = klardrop,
        uiDependencies = uiDependencies
      )
    }

  }
}
