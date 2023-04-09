package com.carlom.klardrop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DeviceSmall(deviceUi: DeviceUi, onClick: () -> Unit) {

  Column(
    modifier = Modifier
      .size(100.dp)
      .clickable(onClick = onClick),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {

    CircleDevice(deviceUi)

    Text(
      text = deviceUi.deviceName,
      color = Color.Black,
      maxLines = 2
    )
  }

}

@Composable
expect fun DeviceLarge(
  deviceUi: DeviceUi, onDeviceActionListener: OnDeviceActionListener
)

@Composable
fun CircleDevice(deviceUi: DeviceUi) {
  Canvas(
    modifier = Modifier.size(60.dp)
  ) {

    drawCircle(Color.LightGray)

  }
}

interface OnDeviceActionListener {
  fun onDeviceClick(deviceUi: DeviceUi)
  fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend)
  fun openFilePicker(deviceUi: DeviceUi)

  sealed interface OnDataToSend {
    data class Text(val text: String) : OnDataToSend
    data class FilesList(val filesPath: List<String>) : OnDataToSend
  }
}