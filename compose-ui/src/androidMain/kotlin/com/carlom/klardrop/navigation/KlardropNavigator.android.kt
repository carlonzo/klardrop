package com.carlom.klardrop.navigation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.toKdDeviceKind
import com.carlom.klardrop.common.connectivity.ConnectivityRestriction
import com.carlom.klardrop.common.permissions.Capability
import kotlinx.serialization.Serializable

@Composable
actual fun KlardropNavigator(
  uiDependencies: UiDependencies,
  discoveryController: DiscoveryController,
  isLargeScreen: Boolean,
  modifier: Modifier,
) {
  val context = LocalContext.current

  // T11: the OS-standard exemption flow. Launching via the activity-result
  // launcher lets us re-snapshot restriction state the moment the system
  // dialog returns (its Allow writes the device-idle whitelist; no broadcast
  // reaches our receivers on every OS version, so we refresh explicitly —
  // same contract as PermissionsMonitor.refresh() after a runtime prompt).
  val batteryExemptionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { discoveryController.refreshConnectivityRestrictions() }

  val onRequestExemption: (ConnectivityRestriction) -> Unit = { restriction ->
    when (restriction) {
      ConnectivityRestriction.BatterySaverBlocking,
      ConnectivityRestriction.BatteryOptimizationNotExempt,
      -> {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
          data = Uri.parse("package:${context.packageName}")
        }
        runCatching { batteryExemptionLauncher.launch(intent) }
      }
      ConnectivityRestriction.MeteredNetworkDenied -> openAppSettings(context)
    }
  }

  if (isLargeScreen) {
    WideLayout(
      modifier = modifier,
      discoveryController = discoveryController,
      uiDependencies = uiDependencies,
      onRequestExemption = onRequestExemption,
      backHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
    )
    return
  }

  // Nav3 back stack: saved across config changes / process death, and NavDisplay
  // routes system back (incl. predictive back) to pop Chat -> Discovery.
  val backStack = rememberNavBackStack(DiscoveryRoute)
  val chatTarget = backStack.lastOrNull() as? ChatRoute

  LaunchedEffect(chatTarget) {
    discoveryController.setActiveChatDeviceId(chatTarget?.deviceId)
  }

  NavDisplay(
    backStack = backStack,
    modifier = modifier,
    onBack = { backStack.removeLastOrNull() },
    entryProvider = entryProvider {
      entry<DiscoveryRoute> {
        val requestedPerms = remember { mutableListOf<String>() }
        val permsLauncher = rememberLauncherForActivityResult(
          ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
          discoveryController.refreshPermissions()
          val activity = context.findActivity()
          val anyPermanentlyDenied = activity != null && results.any { (perm, granted) ->
            !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
          }
          if (anyPermanentlyDenied) openAppSettings(context)
          requestedPerms.clear()
        }

        DiscoveryScreen(
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
          onRequestExemption = onRequestExemption,
        )
      }
      entry<ChatRoute> { route ->
        val vm = remember(route.deviceId) { uiDependencies.deviceChatViewModelFactory(route.deviceId) }
        val state by discoveryController.screenStateFlow.collectAsState()
        val device = state.devices.firstOrNull { it.deviceId == route.deviceId }
        val isOwned = device?.trustStatus == TrustStatus.Trusted
        DeviceChatScreen(
          deviceName = route.deviceName,
          isOwned = isOwned,
          viewModel = vm,
          onBackClicked = { backStack.removeLastOrNull() },
          onOpenFileRequest = { path -> vm.openFileClicked(path) },
          onOpenUrlRequest = { url -> vm.openUrlClicked(url) },
          kind = device?.deviceType?.toKdDeviceKind() ?: KdDeviceKind.Unknown,
        )
      }
    },
  )
}

@Serializable
private data object DiscoveryRoute : NavKey

@Serializable
private data class ChatRoute(val deviceId: String, val deviceName: String) : NavKey

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
