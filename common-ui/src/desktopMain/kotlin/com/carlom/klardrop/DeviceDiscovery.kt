package com.carlom.klardrop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.DragData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.onExternalDrag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
actual fun DeviceDiscovery(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
) {

  var dragging by remember { mutableStateOf(false) }
  var showDropdown by remember { mutableStateOf(false) }

  Box(
    modifier = Modifier.padding(16.dp)
      .fillMaxWidth()
      .clip(shape = RoundedCornerShape(24.dp))
      .background(
        if (dragging) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.tertiary
      )
      .onClick(
        matcher = PointerMatcher.mouse(PointerButton.Primary),
        onClick = {
          onDeviceActionListener.onDeviceClick(deviceUi)
        }
      )
      .onClick(
        matcher = PointerMatcher.mouse(PointerButton.Secondary),
        onClick = { showDropdown = true }
      )
      .onExternalDrag(
        onDragStart = { dragging = true },
        onDragExit = { dragging = false },
        onDrop = {
          dragging = false
          val dragData = it.dragData

          when (dragData) {
            is DragData.FilesList -> {
              println("onDrop ${dragData.readFiles()}")
              onDeviceActionListener.onSendData(deviceUi, OnDataToSend.FilesList(dragData.readFiles()))
            }

            is DragData.Text -> {
              println("onDrop ${dragData.readText()}")
              onDeviceActionListener.onSendData(deviceUi, OnDataToSend.Text(dragData.readText()))
            }
          }
        },
      )
  ) {

    Box {

      Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {

        CircleDevice(deviceUi)

        Spacer(modifier = Modifier.size(16.dp))

        Text(
          text = deviceUi.deviceName,
          color = Color.Black,
          maxLines = 2
        )

      }

      DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {

        Text(
          text = "Send text",
          modifier = Modifier.padding(4.dp)
            .onClick {
              showDropdown = false
              onDeviceActionListener.onSendData(deviceUi, OnDataToSend.Text("Hello from Klardrop!12341"))
            }
        )

        Text(
          text = "Send file",
          modifier = Modifier.padding(4.dp)
            .onClick {

              showDropdown = false
              onDeviceActionListener.openFilePicker(deviceUi)
            }
        )

      }

    }


  }

}