package com.carlom.klardrop.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.android.service.DiscoveryForegroundService
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val klardrop: Klardrop
    get() = appKlardrop()

  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    FileKit.init(this)

    // Keep the "stay discoverable" service in sync with the pref while we're foregrounded: starting
    // from a foreground Activity dodges the background-start restriction, and turning it ON is also
    // where we prompt for POST_NOTIFICATIONS (the service's notification needs it on Android 13+).
    lifecycleScope.launch {
      klardrop.commonComponent.localPropertiesRepository().properties
        .map { it.backgroundDiscoveryEnabled }
        .distinctUntilChanged()
        .collect { enabled ->
          if (enabled) {
            maybeRequestNotificationPermission()
            DiscoveryForegroundService.start(this@MainActivity)
          }
        }
    }

    setContent {
      AppTheme {
        KlardropApp(
          klardrop = klardrop,
          onDiscoveryControllerAvailable = { controller ->
            com.carlom.klardrop.android.debug.AndroidDebugBridge.onDiscoveryControllerAvailable(
              controller,
              klardrop,
            )
          },
        )
      }
    }


  }

  private fun maybeRequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

}