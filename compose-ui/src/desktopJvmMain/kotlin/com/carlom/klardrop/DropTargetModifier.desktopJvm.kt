package com.carlom.klardrop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
  onDragStateChange: (DropHover?) -> Unit,
): Modifier {
  // Key-less `remember` + updated-state refs: the DragAndDropTarget must survive the
  // recompositions its own hover callback triggers, otherwise the OS-level listener is
  // re-registered mid-hover and the target flickers.
  val latestOnDropped by rememberUpdatedState(onDataDropped)
  val latestOnDragState by rememberUpdatedState(onDragStateChange)
  val callback = remember {
    object : DragAndDropTarget {
      // onEntered/onExited, not onStarted: onStarted fires for every target as soon as
      // the drag enters the window, wherever the pointer is.
      override fun onEntered(event: DragAndDropEvent) {
        latestOnDragState(event.hover())
      }

      override fun onExited(event: DragAndDropEvent) {
        latestOnDragState(null)
      }

      override fun onEnded(event: DragAndDropEvent) {
        latestOnDragState(null)
      }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        latestOnDragState(null)
        when (val dragData = event.dragData()) {
          is DragData.FilesList -> {
            val files = dragData.readFiles()
              .map { URI.create(it).toPath().pathString }
              .map { PlatformFile(it) }
            if (files.isNotEmpty()) {
              latestOnDropped(OnDataToSend.FilesList(files))
            }
          }

          is DragData.Text -> {
            val text = dragData.readText()
            if (text.isNotBlank()) {
              latestOnDropped(OnDataToSend.Text(text))
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

// Reading the transferable before the drop is allowed by AWT but may throw on some
// toolkits (X11/XWayland); fall back to a nameless hover then.
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.hover(): DropHover = runCatching {
  when (val data = dragData()) {
    is DragData.FilesList -> DropHover(
      fileNames = data.readFiles().map { URI.create(it).toPath().fileName.toString() },
    )
    is DragData.Text -> DropHover(isText = true)
    else -> DropHover()
  }
}.getOrDefault(DropHover())
