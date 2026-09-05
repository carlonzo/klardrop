package com.carlom.klardrop

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.components.ShareDialog
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
  pendingFiles: List<String>? = null,
  onClearPendingFiles: () -> Unit = {},
  onDiscoveryControllerAvailable: (DiscoveryController) -> Unit = {},
) {
  val uiDependencies = remember { UiDependencies(klardrop.commonComponent) }
  val visibleDevicesController = remember { uiDependencies.discoveryController() }

  var activeShareFiles by remember { mutableStateOf<List<String>?>(null) }
  LaunchedEffect(pendingFiles) {
    if (!pendingFiles.isNullOrEmpty()) {
      activeShareFiles = pendingFiles
    }
  }

  LaunchedEffect(visibleDevicesController) {
    onDiscoveryControllerAvailable(visibleDevicesController)
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

          activeShareFiles?.let { files ->
            ShareDialog(
              files = files,
              discoveryController = visibleDevicesController,
              isLargeScreen = isLargeScreen,
              onDismiss = {
                activeShareFiles = null
                onClearPendingFiles()
              },
            )
          }
        }
      }
    }
  }
}

private val BoxWithConstraintsScope.isLargeScreen: Boolean
  get() = maxWidth > 700.dp
