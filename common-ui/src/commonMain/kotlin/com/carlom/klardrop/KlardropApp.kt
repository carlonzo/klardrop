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
import com.carlom.klardrop.navigation.KlardropNavigator
import com.carlom.klardrop.theme.AppTheme

@Composable
fun KlardropApp(
  klardrop: Klardrop,
) {
  val uiDependencies = remember { UiDependencies(klardrop.commonComponent) }
  val visibleDevicesController = remember { uiDependencies.discoveryController() }

  AppTheme {
    Surface(
      modifier = Modifier.fillMaxSize()
    ) {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        KlardropNavigator(
          uiDependencies = uiDependencies,
          discoveryController = visibleDevicesController,
          isLargeScreen = isLargeScreen,
          modifier = Modifier.fillMaxSize()
        )
      }
    }
  }
}

private val BoxWithConstraintsScope.isLargeScreen: Boolean
  get() = maxWidth > 700.dp