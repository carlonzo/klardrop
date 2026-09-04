package com.carlom.klardrop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.debug.DebugControl
import com.carlom.klardrop.navigation.KlardropNavigator
import com.carlom.klardrop.theme.AppTheme
import com.carlom.klardrop.theme.KdTheme
import com.carlom.klardrop.theme.LocalContentInsets
import com.carlom.klardrop.theme.LocalIsDesktop

@Composable
fun KlardropApp(
  klardrop: Klardrop,
  contentInsets: PaddingValues = PaddingValues(0.dp),
  isDesktop: Boolean = false,
) {
  val uiDependencies = remember { UiDependencies(klardrop.commonComponent) }
  val visibleDevicesController = remember { uiDependencies.discoveryController() }

  LaunchedEffect(visibleDevicesController) {
    DebugControl.bind(visibleDevicesController, klardrop)
  }

  AppTheme {
    // Desktop uses the deep slate shell (#0E1115) so the floating bg1 sidebar
    // sheet reads as elevated; tablets fall back to bg0 so the iPad status-bar
    // strip blends with the regular app background.
    val shellColor = if (isDesktop) KdTheme.colors.bgSidebar else KdTheme.colors.bg0

    Surface(
      modifier = Modifier.fillMaxSize(),
      color = shellColor,
    ) {
      CompositionLocalProvider(
        LocalIsDesktop provides isDesktop,
        LocalContentInsets provides contentInsets,
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
}

private val BoxWithConstraintsScope.isLargeScreen: Boolean
  get() = maxWidth > 700.dp
