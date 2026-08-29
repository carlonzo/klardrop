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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.DiscoveryScreen
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.WideLayout
import com.carlom.klardrop.chat.DeviceChatScreen
import com.carlom.klardrop.common.connectivity.ConnectivityRestriction
import com.carlom.klardrop.common.permissions.Capability

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
    )
    return
  }

  var chatTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

  LaunchedEffect(chatTarget) {
    discoveryController.setActiveChatDeviceId(chatTarget?.first)
  }

  val currentTarget = chatTarget
  if (currentTarget == null) {
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
      modifier = modifier,
      isLargeScreen = false,
      discoveryController = discoveryController,
      uiDependencies = uiDependencies,
      onNavigateToChat = { id, name -> chatTarget = id to name },
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
      onOpenUrlRequest = { url -> vm.openUrlClicked(url) },
    )
  }
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
