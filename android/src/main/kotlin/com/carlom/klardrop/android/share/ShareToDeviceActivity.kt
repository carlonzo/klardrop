package com.carlom.klardrop.android.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.carlom.klardrop.ActivityState
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.android.applicationComponent
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.components.DeviceRow
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdRowState
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.theme.AppTheme
import com.carlom.klardrop.theme.KdTheme
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

class ShareToDeviceActivity : AppCompatActivity() {

  @Inject
  lateinit var klardrop: Klardrop

  private lateinit var shareToDeviceController: ShareToDeviceController

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationComponent().inject(this)

    shareToDeviceController = ShareToDeviceController(klardrop.commonComponent)

    setContent {
      val devices by shareToDeviceController.devicesFlow.collectAsState(emptyList<DeviceUi>())

      AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(KdTheme.spacing.s4),
            verticalArrangement = Arrangement.spacedBy(KdTheme.spacing.s3),
          ) {
            Text(
              text = "Share with",
              style = KdTheme.typography.headline.copy(color = KdTheme.colors.text),
            )

            val deviceList = devices.toList()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(KdTheme.spacing.s2)) {
              items(
                count = deviceList.size,
                key = { index -> deviceList[index].deviceId },
              ) { index ->
                val device = deviceList[index]
                val isTrusted = device.trustStatus == TrustStatus.Trusted
                DeviceRow(
                  name = device.deviceName,
                  subText = device.activityState.label(),
                  kind = device.deviceType.toShareKind(),
                  avatarStyle = if (isTrusted) KdAvatarStyle.Tinted else KdAvatarStyle.Neutral,
                  status = device.reachability.toKdStatus(),
                  rowState = if (device.activityState is ActivityState.Sending) KdRowState.Pairing else KdRowState.Idle,
                  onClick = { shareDevice(device) },
                )
              }
            }
          }
        }
      }
    }

    when (intent?.action) {
      Intent.ACTION_SEND -> {
        if ("text/plain" == intent.type) {
          log("ShareToDeviceActivity", "Handling text $intent")
          handleSendText(intent)
        } else {
          log("ShareToDeviceActivity", "Handling file $intent")
          handleSendFile(intent)
        }
      }

      Intent.ACTION_SEND_MULTIPLE -> {
        log("ShareToDeviceActivity", "Handling multiple files $intent")
        handleSendMultipleFiles(intent)
      }

      else -> {
        log("ShareToDeviceActivity", "Unhandled intent: $intent")
      }
    }
  }

  private fun shareDevice(deviceUi: DeviceUi) {
    shareToDeviceController.onDeviceClick(deviceUi)

    lifecycleScope.launch {
      shareToDeviceController.devicesFlow
        .mapNotNull {
          it.firstOrNull { candidate -> candidate.deviceId == deviceUi.deviceId }
        }
        .filter { it.activityState is ActivityState.SentCompleted }
        .onEach { log("ShareToDeviceActivity", "filtered $it") }
        .firstOrNull()

      log("ShareToDeviceActivity", "Received sent completed, finishing activity")
      finish()
    }
  }

  private fun handleSendText(intent: Intent) {
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
      shareToDeviceController.initializeItemToShare(OnDataToSend.Text(it))
    }
  }

  private fun handleSendFile(intent: Intent) {
    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
    }
    uri?.let {
      shareToDeviceController.initializeItemToShare(OnDataToSend.FilesList(listOf(PlatformFile(it))))
    }
  }

  private fun handleSendMultipleFiles(intent: Intent) {
    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
      @Suppress("DEPRECATION", "UNCHECKED_CAST")
      intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
        ?.let { it as ArrayList<Uri> }
    }
    list?.let {
      shareToDeviceController.initializeItemToShare(OnDataToSend.FilesList(it.map { uri -> PlatformFile(uri) }))
    }
  }

  override fun onDestroy() {
    shareToDeviceController.dispose()
    super.onDestroy()
  }
}

private fun DeviceType.toShareKind(): KdDeviceKind = when (this) {
  DeviceType.MOBILE -> KdDeviceKind.Android
  DeviceType.DESKTOP -> KdDeviceKind.Pc
  DeviceType.UNKNOWN -> KdDeviceKind.Unknown
}

private fun Reachability.toKdStatus(): KdStatus? = when (this) {
  Reachability.Reachable -> KdStatus.Ok
  Reachability.Unreachable -> KdStatus.Err
  Reachability.Probing,
  Reachability.Unknown -> null
}

private fun ActivityState.label(): String = when (this) {
  ActivityState.Idle -> ""
  is ActivityState.Sending -> "Sending… ${progressPercentage}%"
  is ActivityState.SentCompleted -> if (error) "Failed" else "Sent"
}
