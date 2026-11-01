package com.carlom.klardrop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Multiplatform drop target for "send to this device / chat" surfaces. Caller decides
 * what to do with the dropped payload (e.g. forward to a device, push into a chat
 * ViewModel) and how to render the hover state via [onDragStateChange]: a [DropHover]
 * while the pointer is over the target, null once it leaves or the drag ends.
 *
 * Currently a no-op on Android/iOS. Native drag-and-drop into the app from the OS
 * file manager is desktop-only for now; the picker-based attach flow covers mobile.
 */
@Composable
internal expect fun Modifier.dropTargetForSending(
  onDataDropped: (OnDataToSend) -> Unit,
  onDragStateChange: (DropHover?) -> Unit = {},
): Modifier

/** What is being dragged over a drop target. [fileNames] is empty when unreadable before the drop. */
internal data class DropHover(
  val fileNames: List<String> = emptyList(),
  val isText: Boolean = false,
)

internal fun dropOverlayLabel(hover: DropHover, deviceName: String): String = when {
  hover.fileNames.size == 1 -> "Send file ${hover.fileNames.single()} to $deviceName"
  hover.fileNames.size > 1 -> "Send ${hover.fileNames.size} files to $deviceName"
  hover.isText -> "Send text to $deviceName"
  else -> "Send file to $deviceName"
}
