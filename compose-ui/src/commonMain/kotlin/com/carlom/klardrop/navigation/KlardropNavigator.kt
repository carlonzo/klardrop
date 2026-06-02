package com.carlom.klardrop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.UiDependencies

@Composable
expect fun KlardropNavigator(
  uiDependencies: UiDependencies,
  discoveryController: DiscoveryController,
  isLargeScreen: Boolean,
  modifier: Modifier = Modifier,
)
