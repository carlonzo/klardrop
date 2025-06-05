package com.carlom.klardrop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import io.github.vinceglb.filekit.PlatformFile
import java.net.URI
import kotlin.io.path.pathString
import kotlin.io.path.toPath

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal actual fun Modifier.deviceAdditions(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
): Modifier {

  var dragging by remember { mutableStateOf(false) }

  val callback = remember {
    object : DragAndDropTarget {
      override fun onStarted(event: DragAndDropEvent) {
        dragging = true
      }

      override fun onEnded(event: DragAndDropEvent) {
        dragging = false
      }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        when (val dragData = event.dragData()) {
          is DragData.FilesList -> {
            val filePaths = dragData.readFiles()
              .map { URI.create(it).toPath().pathString }
              .map { PlatformFile(it) }

            onDeviceActionListener.onSendData(deviceUi, OnDataToSend.FilesList(filePaths))
          }

          is DragData.Text -> {
            onDeviceActionListener.onSendData(deviceUi, OnDataToSend.Text(dragData.readText()))
          }
        }

        return true
      }
    }
  }

  return this
    .background(
      if (dragging) MaterialTheme.colorScheme.tertiaryContainer
      else MaterialTheme.colorScheme.tertiary
    ).dragAndDropTarget(
      target = callback,
      shouldStartDragAndDrop = { event -> true }
    )

//    .dragAndDropTarget(
//      target = {
//        dragging = false
//        @Suppress("MoveVariableDeclarationIntoWhen")
//        val dragData = it.dragData
//
//        when (dragData) {
//          is DragData.FilesList -> {
//            val filePaths = dragData.readFiles().map { URI.create(it).toPath().pathString }
//            println("onDrop $filePaths")
//
//            onDeviceActionListener.onSendData(deviceUi, OnDataToSend.FilesList(filePaths))
//          }
//
//          is DragData.Text -> {
//            println("onDrop ${dragData.readText()}")
//            onDeviceActionListener.onSendData(deviceUi, OnDataToSend.Text(dragData.readText()))
//          }
//        }
//      },
//    )
}
