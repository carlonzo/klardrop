package com.carlom.klardrop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.DiscoveryScreen
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.WideLayout
import com.carlom.klardrop.chat.DeviceChatScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable

@Serializable
sealed interface KlardropNavKey : NavKey

@Serializable
data object DiscoveryRoute : KlardropNavKey

@Serializable
data class ChatRoute(
  val deviceId: String,
  val deviceName: String,
) : KlardropNavKey

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

  val backStack = rememberNavBackStack(DiscoveryRoute)

  LaunchedEffect(backStack, discoveryController) {
    snapshotFlow { (backStack.lastOrNull() as? ChatRoute)?.deviceId }
      .distinctUntilChanged()
      .collect { discoveryController.setActiveChatDeviceId(it) }
  }

  NavDisplay(
    backStack = backStack,
    modifier = modifier,
    onBack = { backStack.removeLastOrNull() },
    entryProvider = entryProvider {
      entry<DiscoveryRoute> {
        DiscoveryScreen(
          modifier = Modifier,
          isLargeScreen = false,
          discoveryController = discoveryController,
          uiDependencies = uiDependencies,
          onNavigateToChat = { id, name -> backStack.add(ChatRoute(id, name)) },
        )
      }
      entry<ChatRoute> { key ->
        val vm = remember(key.deviceId) {
          uiDependencies.deviceChatViewModelFactory(key.deviceId)
        }
        val state by discoveryController.screenStateFlow.collectAsState()
        val isOwned = state.devices
          .firstOrNull { it.deviceId == key.deviceId }
          ?.trustStatus == TrustStatus.Trusted
        DeviceChatScreen(
          deviceName = key.deviceName,
          isOwned = isOwned,
          viewModel = vm,
          onBackClicked = { backStack.removeLastOrNull() },
          onOpenFileRequest = { path -> vm.openFileClicked(path) },
        )
      }
    }
  )
}
