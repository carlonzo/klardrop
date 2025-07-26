package com.carlom.klardrop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.utils.log


@Composable
fun DeviceDiscovery(
  deviceUi: DeviceUi, isLargeScreen: Boolean, onDeviceActionListener: OnDeviceActionListener
) {
  if (isLargeScreen) {
    DeviceLarge(deviceUi, onDeviceActionListener)
  } else {
    DeviceSmall(deviceUi, onDeviceActionListener)
  }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeviceSmall(deviceUi: DeviceUi, onDeviceActionListener: OnDeviceActionListener) {

  Column(
    modifier = Modifier
      .padding(16.dp)
      .sizeIn(maxWidth = 100.dp)
      .combinedClickable(
        onClick = {
          log("Device clicked ${deviceUi.deviceName}")
          onDeviceActionListener.onDeviceClick(deviceUi)
        },
      ),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {

    Box {
      CircleDevice(deviceUi)
      
      // Trust indicator positioned at bottom-right of circle
      TrustIndicatorDot(
        trustStatus = deviceUi.trustStatus,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(4.dp)
      )
    }

    Text(
      text = deviceUi.deviceName,
      maxLines = 2
    )
    
    // Show trust status badge below device name
    TrustStatusBadge(
      trustStatus = deviceUi.trustStatus,
      showLabel = false,
      modifier = Modifier.padding(top = 4.dp)
    )
  }

}

@Composable
internal fun DeviceLarge(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
) {

  Box(
    modifier = Modifier.padding(16.dp)
      .fillMaxWidth()
      .clip(shape = RoundedCornerShape(24.dp))
      .clickable { onDeviceActionListener.onDeviceClick(deviceUi) }
      .deviceAdditions(deviceUi, onDeviceActionListener)
  ) {

    Box {

      Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {

        Box {
          CircleDevice(deviceUi)
          
          // Trust indicator on device circle
          TrustIndicatorDot(
            trustStatus = deviceUi.trustStatus,
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(4.dp)
          )
        }

        Spacer(modifier = Modifier.size(12.dp))

        Column {
          deviceUi.connectionTypes.forEach {
            Text(
              text = it.name,
              color = Color.Black,
            )
          }
        }

        Spacer(modifier = Modifier.size(16.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = deviceUi.deviceName,
            color = Color.Black,
            maxLines = 2
          )
          
          Spacer(modifier = Modifier.size(4.dp))
          
          // Trust status and permissions
          DeviceTrustIndicator(
            deviceUi = deviceUi,
            showDetails = true
          )
        }

      }

    }


  }

}

@Composable
private fun CircleDevice(deviceUi: DeviceUi) {
  val isSending = deviceUi.activityState is ActivityState.Sending
  val targetCircleSize = if (isSending) 60.dp else 70.dp

  val circleSize by animateDpAsState(targetValue = targetCircleSize)


  Box(modifier = Modifier.size(circleSize)) {
    if (isSending) {
      val progress = (deviceUi.activityState as ActivityState.Sending).progressPercentage / 100.0f
      CircularProgressIndicator(
        progress = { progress },
        modifier = Modifier.size(circleSize),
        color = Color.Blue,
      )
    }

    Canvas(
      modifier = Modifier.size(circleSize)
    ) {
      drawCircle(Color.LightGray, radius = (targetCircleSize / 2 - 5.dp).toPx())

    }
  }

}

@Composable
internal expect fun Modifier.deviceAdditions(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
): Modifier


interface OnDeviceActionListener {
  fun onDeviceClick(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

  fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {
    throw IllegalStateException("Not implemented")
  }

}