package com.carlom.klardrop.android

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carlom.klardrop.ActionUi
import com.carlom.klardrop.DiscoveryDashboard
import com.carlom.klardrop.FilePickerFactory
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.ShowVisibleDevicesController
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

class MainActivity : AppCompatActivity() {

  private lateinit var showVisibleDevicesController: ShowVisibleDevicesController
  private var actionUi: ActionUi? = null

  @Inject
  lateinit var klardrop: Klardrop

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationComponent().inject(this)



    setContent {

      val uiDependencies = remember {
        object : UiDependencies {
          override fun filePickerFactory(): FilePickerFactory {
            return FilePickerFactory()
          }

        }
      }

      AppTheme {

        KlardropApp(
          klardrop,
          uiDependencies
        )

      }


    }


  }




  override fun onDestroy() {
    showVisibleDevicesController.dispose()
    super.onDestroy()
  }

}