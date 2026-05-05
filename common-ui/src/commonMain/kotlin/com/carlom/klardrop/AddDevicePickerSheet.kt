package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddDevicePickerSheet(
  candidates: List<DeviceUi>,
  onDismiss: () -> Unit,
  onPick: (DeviceUi) -> Unit
) {
  ModalBottomSheetLayout(
    sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    sheetBackgroundColor = MaterialTheme.colorScheme.surface,
    sheetContentColor = MaterialTheme.colorScheme.contentColorFor(MaterialTheme.colorScheme.surface),
    sheetContent = {
      Column(
        modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        AddPickerSheetHandle()
        Text(
          "Add a device",
          style = MaterialTheme.typography.titleLarge
        )
        Text(
          "Pick a nearby device to pair with. Trusted devices auto-share clipboard, Wi-Fi logins and notifications.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (candidates.isEmpty()) {
          Text(
            "No nearby devices right now. Make sure Klardrop is open on the device you want to pair, and that both are on the same Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 360.dp)
          ) {
            items(items = candidates, key = { it.deviceId }) { device ->
              PickerRow(device = device, onClick = { onPick(device) })
            }
          }
        }

        Row(
          horizontalArrangement = Arrangement.End,
          modifier = Modifier.fillMaxWidth()
        ) {
          TextButton(onClick = onDismiss) { Text("Close") }
        }
      }
    },
    sheetState = rememberModalBottomSheetState(ModalBottomSheetValue.Expanded)
  ) { }
}

@Composable
private fun PickerRow(device: DeviceUi, onClick: () -> Unit) {
  val pairing = device.trustStatus == TrustStatus.Pairing
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = !pairing, onClick = onClick)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = deviceIcon(device.deviceType),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(18.dp)
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = device.deviceName,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = if (pairing) "Pairing…" else "Tap to pair",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
internal fun AddDevicePlaceholderSurface(
  isLargeScreen: Boolean,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth()
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 12.dp),
      contentAlignment = if (isLargeScreen) Alignment.CenterStart else Alignment.Center
    ) {
      AddDevicePlaceholderCard(isLargeScreen = isLargeScreen, onClick = onClick)
    }
  }
}

@Composable
private fun AddDevicePlaceholderCard(isLargeScreen: Boolean, onClick: () -> Unit) {
  if (isLargeScreen) {
    AddDevicePlaceholderLarge(onClick)
  } else {
    AddDevicePlaceholderSmall(onClick)
  }
}

@Composable
private fun AddDevicePlaceholderSmall(onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(20.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 14.dp, horizontal = 8.dp)
      .width(96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Box(
      modifier = Modifier
        .size(56.dp)
        .border(
          width = 1.5.dp,
          color = MaterialTheme.colorScheme.outline,
          shape = CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Filled.Add,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(24.dp)
      )
    }
    Text(
      text = "Add a device",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center
    )
    Text(
      text = "Pair from nearby",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      textAlign = TextAlign.Center
    )
  }
}

@Composable
private fun AddDevicePlaceholderLarge(onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(20.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(56.dp)
          .border(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.outline,
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Filled.Add,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(24.dp)
        )
      }

      Spacer(modifier = Modifier.size(16.dp))

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "Add a device",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "Pair from nearby devices",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
internal fun SidebarAddDeviceRow(onClick: () -> Unit) {
  Surface(
    color = androidx.compose.ui.graphics.Color.Transparent,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 10.dp, vertical = 1.dp)
      .clickable(onClick = onClick)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .border(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.outline,
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Filled.Add,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(18.dp)
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Add a device",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = "Pair from nearby",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
private fun AddPickerSheetHandle() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(bottom = 4.dp),
    contentAlignment = Alignment.Center
  ) {
    Box(
      modifier = Modifier
        .size(width = 36.dp, height = 4.dp)
        .background(
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
          shape = RoundedCornerShape(2.dp)
        )
    )
  }
}
