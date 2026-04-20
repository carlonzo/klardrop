package com.carlom.klardrop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme

@Composable
fun KlardropApp(
  klardrop: Klardrop,
  // uiDependencies is now created inside, or passed as the new class type
  // For simplicity, let's assume it's created here based on klardrop.commonComponent
) {
  val uiDependencies = remember { UiDependencies(klardrop.commonComponent) }
  val visibleDevicesController = remember { uiDependencies.discoveryController() }

  AppTheme {
    Surface(
      modifier = Modifier.fillMaxSize()
    ) {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLargeScreen = isLargeScreen

        if (isLargeScreen) {
          WideLayout(
            modifier = Modifier.fillMaxSize(),
            discoveryController = visibleDevicesController,
            uiDependencies = uiDependencies
          )
        } else {
          DiscoveryScreen(
            modifier = Modifier.fillMaxSize(),
            isLargeScreen = false,
            discoveryController = visibleDevicesController,
            uiDependencies = uiDependencies
          )
        }
      }
    }
  }
}

private val BoxWithConstraintsScope.isLargeScreen: Boolean
  get() = maxWidth > 700.dp