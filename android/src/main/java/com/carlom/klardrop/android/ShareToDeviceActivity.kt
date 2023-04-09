package com.carlom.klardrop.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.carlom.klardrop.DeviceDiscovery
import com.carlom.klardrop.DeviceUi
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.OnDeviceActionListener
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.theme.AppTheme
import javax.inject.Inject

class ShareToDeviceActivity : AppCompatActivity() {

  @Inject
  lateinit var klardrop: Klardrop

  private lateinit var shareToDeviceController: ShareToDeviceController

  @OptIn(ExperimentalLayoutApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationComponent().inject(this)

    shareToDeviceController = ShareToDeviceController(klardrop.commonComponent)

    setContent {

      val devices by shareToDeviceController.devicesFlow.collectAsState(emptyList())

      AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {

          Text("Share with:")

          FlowRow {
            devices.forEach {

              DeviceDiscovery(it, onDeviceActionListener)

            }
          }

        }
      }

    }

    when {
      intent?.action == Intent.ACTION_SEND -> {
        if ("text/plain" == intent.type) {
          log("ShareToDeviceActivity", "Handling text $intent")
          handleSendText(intent) // Handle text being sent
        } else {
          log("ShareToDeviceActivity", "Handling file $intent")
          handleSendFile(intent) // Handle single image being sent
        }
      }

      intent?.action == Intent.ACTION_SEND_MULTIPLE -> {
        log("ShareToDeviceActivity", "Handling multiple files $intent")
        handleSendMultipleFiles(intent)
      }

      else -> {
        // Handle other intents, such as being started from the home screen
        log("ShareToDeviceActivity", "Unhandled intent: $intent")
      }
    }
  }

  private val onDeviceActionListener = object : OnDeviceActionListener {
    override fun onDeviceClick(deviceUi: DeviceUi) {
      shareToDeviceController.onDeviceClick(deviceUi)

//      finish()
    }
  }

  private fun handleSendText(intent: Intent) {
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
      shareToDeviceController.initializeItemToShare(OnDataToSend.Text(it))
    }
  }

  private fun handleSendFile(intent: Intent) {
    (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
      shareToDeviceController.initializeItemToShare(OnDataToSend.FilesList(listOf(it.toString())))
    }
  }

  private fun handleSendMultipleFiles(intent: Intent) {
    intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.let {
      shareToDeviceController.initializeItemToShare(OnDataToSend.FilesList(it.toList().map { it.toString() }))
    }
  }

  override fun onDestroy() {
    shareToDeviceController.dispose()
    super.onDestroy()
  }

}