package com.carlom.klardrop.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Button
import androidx.compose.material.Text
import com.carlom.klardrop.DiscoveryDashboard
import com.carlom.klardrop.ShowVisibleDevicesController
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.device_selection.DeviceSelectionDashabord
import com.carlom.klardrop.device_selection.DevicesSelectionController

class MainActivity : AppCompatActivity() {

  private lateinit var devicesSelectionController: DevicesSelectionController

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies(this))
    k.init()

    val discoveryController = ShowVisibleDevicesController(k.commonComponent)
    devicesSelectionController = DevicesSelectionController(k.commonComponent)

    setContent {

      Column {

        DiscoveryDashboard(discoveryController)

        Button(onClick = {
          pickMedia.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
        }) {
          Text("Select Image")
        }

        DeviceSelectionDashabord(devicesSelectionController)

      }


    }


  }


  val pickMedia = this.registerForActivityResult(PickVisualMedia()) { uri ->
    // Callback is invoked after the user selects a media item or closes the
    // photo picker.
    if (uri != null) {
      Log.d("PhotoPicker", "Selected URI: $uri")
      selectFile(uri)

    } else {
      Log.d("PhotoPicker", "No media selected")
    }
  }

  private fun selectFile(uri: Uri){
    contentResolver.query(uri, null, null, null, null).use { cursor ->
      cursor?: run {
        Log.d("PhotoPicker", "No media selected")
        return
      }

      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
      cursor.moveToFirst()

      val mimetype = contentResolver.getType(uri)

      val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype)

      val fileName  = cursor.getString(nameIndex)
      val filesize = cursor.getLong(sizeIndex)

      devicesSelectionController.stringUri = uri.toString()
      devicesSelectionController.fileSize = filesize
      devicesSelectionController.filename = fileName
    }
  }

}