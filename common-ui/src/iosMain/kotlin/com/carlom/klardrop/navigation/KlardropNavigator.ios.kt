package com.carlom.klardrop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.DiscoveryScreen
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.WideLayout
import com.carlom.klardrop.chat.DeviceChatScreen

@Composable
actual fun KlardropNavigator(
  uiDependencies: UiDependencies,
  discoveryController: DiscoveryController,
  isLargeScreen: Boolean,
  modifier: Modifier,
) {
  if (isLargeScreen) {
    WideLayout(
      modifier = modifier,
      discoveryController = discoveryController,
      uiDependencies = uiDependencies,
    )
    return
  }

  var chatTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

  LaunchedEffect(chatTarget) {
    discoveryController.setActiveChatDeviceId(chatTarget?.first)
  }

  val currentTarget = chatTarget
  if (currentTarget == null) {
    DiscoveryScreen(
      modifier = modifier,
      isLargeScreen = false,
      discoveryController = discoveryController,
      uiDependencies = uiDependencies,
      onNavigateToChat = { id, name -> chatTarget = id to name },
    )
  } else {
    val (deviceId, deviceName) = currentTarget
    val vm = remember(deviceId) { uiDependencies.deviceChatViewModelFactory(deviceId) }
    val state by discoveryController.screenStateFlow.collectAsState()
    val isOwned = state.devices
      .firstOrNull { it.deviceId == deviceId }
      ?.trustStatus == TrustStatus.Trusted
    DeviceChatScreen(
      deviceName = deviceName,
      isOwned = isOwned,
      viewModel = vm,
      onBackClicked = { chatTarget = null },
      onOpenFileRequest = { path -> vm.openFileClicked(path) },
    )
  }
}
