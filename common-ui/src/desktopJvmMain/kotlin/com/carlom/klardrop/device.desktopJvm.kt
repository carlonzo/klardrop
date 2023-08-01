package com.carlom.klardrop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.onClick
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.DragData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.onExternalDrag
import androidx.compose.ui.unit.dp
import java.net.URI
import kotlin.io.path.pathString
import kotlin.io.path.toPath

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun Modifier.deviceAdditions(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
): Modifier {

  var dragging by remember { mutableStateOf(false) }

  return this
    .background(
      if (dragging) MaterialTheme.colorScheme.tertiaryContainer
      else MaterialTheme.colorScheme.tertiary
    ).onClick(
      matcher = PointerMatcher.mouse(PointerButton.Primary),
      onClick = {
        onDeviceActionListener.onDeviceClick(deviceUi)
      }
    ).onExternalDrag(
      onDragStart = { dragging = true },
      onDragExit = { dragging = false },
      onDrop = {
        dragging = false
        @Suppress("MoveVariableDeclarationIntoWhen")
        val dragData = it.dragData

        when (dragData) {
          is DragData.FilesList -> {
            val filePaths = dragData.readFiles().map { URI.create(it).toPath().pathString }
            println("onDrop $filePaths")

            onDeviceActionListener.onSendData(deviceUi, OnDataToSend.FilesList(filePaths))
          }

          is DragData.Text -> {
            println("onDrop ${dragData.readText()}")
            onDeviceActionListener.onSendData(deviceUi, OnDataToSend.Text(dragData.readText()))
          }
        }
      },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal actual fun BoxScope.DeviceContent(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
) {

  var showDropdown by remember { mutableStateOf(false) }

//    .onClick(
//      matcher = PointerMatcher.mouse(PointerButton.Secondary),
//      onClick = { showDropdown = true }
//    )

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