package com.carlom.klardrop.android

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carlom.klardrop.ActionUi
import com.carlom.klardrop.DiscoveryDashboard
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.ShowVisibleDevicesController
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import javax.inject.Inject

class MainActivity : AppCompatActivity() {

  private lateinit var showVisibleDevicesController: ShowVisibleDevicesController
  private var actionUi: ActionUi? = null

  @Inject
  lateinit var klardrop: Klardrop

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationComponent().inject(this)

    showVisibleDevicesController = ShowVisibleDevicesController(klardrop.commonComponent)

    setContent {

      AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          DiscoveryDashboard(
            modifier = Modifier.fillMaxSize(),
            showVisibleDevicesController = showVisibleDevicesController
          )

        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.CREATED) {
        showVisibleDevicesController.actionsFlow.collect { action ->
          actionUi = action
          when (action) {
            is ActionUi.OpenFilePicker -> pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
          }

        }
      }
    }
  }


  private val pickMedia = this.registerForActivityResult(PickVisualMedia()) { uri ->
    // Callback is invoked after the user selects a media item or closes the
    // photo picker.
    if (uri != null) {
      showVisibleDevicesController.onSendData(
        (actionUi as? ActionUi.OpenFilePicker)?.deviceUi!!,
        OnDataToSend.FilesList(listOf(uri.toString()))
      )

    } else {
      Log.d("PhotoPicker", "No media selected")
    }


  }

  override fun onDestroy() {
    showVisibleDevicesController.dispose()
    super.onDestroy()
  }

}