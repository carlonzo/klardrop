package com.carlom.klardrop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
internal actual fun Modifier.dropTargetForSending(
  onDataDropped: (OnDataToSend) -> Unit,
  onDragStateChange: (Boolean) -> Unit,
): Modifier {
  // Hold the latest callbacks in single-instance refs — `remember` the DragAndDropTarget
  // so we don't re-register on every recomposition (which would briefly drop the OS-level
  // listener and make the target flicker mid-hover).
  val callback = remember(onDataDropped, onDragStateChange) {
    object : DragAndDropTarget {
      override fun onStarted(event: DragAndDropEvent) {
        onDragStateChange(true)
      }

      override fun onEnded(event: DragAndDropEvent) {
        onDragStateChange(false)
      }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        when (val dragData = event.dragData()) {
          is DragData.FilesList -> {
            val files = dragData.readFiles()
              .map { URI.create(it).toPath().pathString }
              .map { PlatformFile(it) }
            if (files.isNotEmpty()) {
              onDataDropped(OnDataToSend.FilesList(files))
            }
          }

          is DragData.Text -> {
            val text = dragData.readText()
            if (text.isNotBlank()) {
              onDataDropped(OnDataToSend.Text(text))
            }
          }
        }
        return true
      }
    }
  }

  return this.dragAndDropTarget(
    target = callback,
    shouldStartDragAndDrop = { true },
  )
}
