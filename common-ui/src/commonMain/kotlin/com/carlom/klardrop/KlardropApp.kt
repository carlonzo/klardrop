package com.carlom.klardrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.Klardrop

@Composable
fun KlardropApp(
  klardrop: Klardrop,
  uiDependencies: UiDependencies
) {

  val visibleDevicesController = remember { DiscoveryController(klardrop.commonComponent) }

  Surface(
    modifier = Modifier.fillMaxSize()
  ) {

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {

      val isLargeScreen = isLargeScreen

      if (isLargeScreen) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {

          Text("Hello in Klardrop")

          DiscoveryScreen(
            modifier = Modifier
              .fillMaxWidth(fraction = 0.75f)
              .fillMaxHeight(),
            isLargeScreen = isLargeScreen,
            uiDependencies = uiDependencies,
            discoveryController = visibleDevicesController
          )
        }
      } else {
        DiscoveryScreen(
          discoveryController = visibleDevicesController,
          isLargeScreen = isLargeScreen,
          uiDependencies = uiDependencies
        )
      }

    }

  }

}

private val BoxWithConstraintsScope.isLargeScreen: Boolean
  get() = maxWidth > 700.dp