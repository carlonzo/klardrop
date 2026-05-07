package com.carlom.klardrop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun Modifier.dropTargetForSending(
  onDataDropped: (OnDataToSend) -> Unit,
  onDragStateChange: (Boolean) -> Unit,
): Modifier = this
