package com.carlom.klardrop.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
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
import com.carlom.klardrop.common.permissions.Capability
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
        val context = LocalContext.current
        // RequestMultiplePermissions covers the bundled runtime perms behind a
        // single capability (e.g. the three BLUETOOTH_* perms). After the
        // dialog returns we check whether anything was permanently denied
        // ("Don't ask again") and fall through to system Settings as the
        // user's only remaining recourse.
        val requestedPerms = remember { mutableListOf<String>() }
        val permsLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
          val activity = context.findActivity()
          val anyPermanentlyDenied = activity != null && results.any { (perm, granted) ->
            !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
          }
          if (anyPermanentlyDenied) openAppSettings(context)
          requestedPerms.clear()
        }

        DiscoveryScreen(
          modifier = Modifier,
          isLargeScreen = false,
          discoveryController = discoveryController,
          uiDependencies = uiDependencies,
          onNavigateToChat = { id, name -> backStack.add(ChatRoute(id, name)) },
          onRequestCapability = { capability ->
            val perms = capability.androidPermissions()
            if (perms.isEmpty()) {
              openAppSettings(context)
            } else {
              requestedPerms.clear()
              requestedPerms.addAll(perms)
              permsLauncher.launch(perms.toTypedArray())
            }
          },
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
          onOpenUrlRequest = { url -> vm.openUrlClicked(url) },
        )
      }
    }
  )
}

private fun Capability.androidPermissions(): List<String> = when (this) {
  Capability.BLUETOOTH -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    listOf(
      Manifest.permission.BLUETOOTH_SCAN,
      Manifest.permission.BLUETOOTH_ADVERTISE,
      Manifest.permission.BLUETOOTH_CONNECT,
    )
  } else emptyList()
  Capability.LOCATION -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
  } else emptyList()
  Capability.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    listOf(Manifest.permission.POST_NOTIFICATIONS)
  } else emptyList()
  Capability.LOCAL_NETWORK,
  Capability.NEARBY_WIFI_DEVICES -> emptyList()
}

private fun openAppSettings(context: Context) {
  val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
    data = Uri.fromParts("package", context.packageName, null)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }
  runCatching { context.startActivity(intent) }
}

private fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is android.content.ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}
