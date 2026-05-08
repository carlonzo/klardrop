package com.carlom.klardrop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Multiplatform drop target for "send to this device / chat" surfaces. Caller decides
 * what to do with the dropped payload (e.g. forward to a device, push into a chat
 * ViewModel) and how to render the hover state via [onDragStateChange] — usually a
 * background tint.
 *
 * Currently a no-op on Android/iOS. Native drag-and-drop into the app from the OS
 * file manager is desktop-only for now; the picker-based attach flow covers mobile.
 */
@Composable
internal expect fun Modifier.dropTargetForSending(
  onDataDropped: (OnDataToSend) -> Unit,
  onDragStateChange: (Boolean) -> Unit = {},
): Modifier
