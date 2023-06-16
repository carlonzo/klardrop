package com.carlom.klardrop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.utils.log

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceSmall(deviceUi: DeviceUi, onDeviceActionListener: OnDeviceActionListener) {

  Column(
    modifier = Modifier
      .padding(16.dp)
      .sizeIn(maxWidth = 100.dp)
      .combinedClickable(
        onClick = {
          log("Device clicked ${deviceUi.deviceName}")
          onDeviceActionListener.onDeviceClick(deviceUi)
//          onDeviceActionListener.onSendData(deviceUi, OnDataToSend.Text("Hello World"))
        },
        onLongClick = {
          onDeviceActionListener.openFilePicker(deviceUi)
        }
      ),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {

    CircleDevice(deviceUi)

    Text(
      text = deviceUi.deviceName,
      maxLines = 2
    )
  }

}

@Composable
expect fun DeviceDiscovery(
  deviceUi: DeviceUi, onDeviceActionListener: OnDeviceActionListener
)

@Composable
fun CircleDevice(deviceUi: DeviceUi) {
  val isSending = deviceUi.activityState is ActivityState.Sending
  val targetCircleSize = if (isSending) 60.dp else 70.dp

  val circleSize by animateDpAsState(targetValue = targetCircleSize)


  Box(modifier = Modifier.size(circleSize)) {
    if (isSending) {
      val progress = (deviceUi.activityState as ActivityState.Sending).progressPercentage / 100.0f
      CircularProgressIndicator(progress, modifier = Modifier.size(circleSize), color = Color.Blue)
    }

    Canvas(
      modifier = Modifier.size(circleSize)
    ) {
      drawCircle(Color.LightGray, radius = (targetCircleSize / 2 - 5.dp).toPx())

    }
  }

}

interface OnDeviceActionListener {
  fun onDeviceClick(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

  fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {
    throw IllegalStateException("Not implemented")
  }

  fun openFilePicker(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }
}