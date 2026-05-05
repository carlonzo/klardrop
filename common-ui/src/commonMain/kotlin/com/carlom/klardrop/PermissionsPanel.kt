package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.permissions.Capability
import com.carlom.klardrop.common.permissions.CapabilityStatus
import com.carlom.klardrop.common.permissions.EducationalNote
import com.carlom.klardrop.common.permissions.PermissionsState

/**
 * Setup checklist surfaced at the top of the discovery screen until the
 * platform reports everything actionable as Granted. Two kinds of rows:
 *
 *  - **Capability rows**: missing OS permissions with a "Grant" button. Tapping
 *    [onRequestCapability] hands off to platform-specific UI that knows how
 *    to launch the permission flow or deep-link to system Settings.
 *
 *  - **Educational notes**: warnings about prompts the OS will throw at us
 *    that we can't pre-empt or detect (the macOS Application Firewall is the
 *    canonical example). The user can dismiss each one once read.
 *
 * The panel hides itself entirely once [PermissionsState.isComplete] is true
 * and all educational notes have been dismissed.
 */
@Composable
fun PermissionsPanel(
  state: PermissionsState,
  onRequestCapability: (Capability) -> Unit,
  modifier: Modifier = Modifier,
) {
  // Dismissed-note ids persist across the activity lifecycle but not the install
  // — that's intentional, since the underlying conditions (e.g. macOS firewall
  // behaviour) are stable for the install and don't need to be re-shown.
  var dismissedNotes by rememberSaveable { mutableStateOf(setOf<String>()) }

  val pendingCapabilities = remember(state) {
    state.capabilities
      .filter { (_, status) -> status == CapabilityStatus.Denied || status == CapabilityStatus.Unknown }
      .keys
      .toList()
  }
  val visibleNotes = remember(state, dismissedNotes) {
    state.educationalNotes.filterNot { it.id in dismissedNotes }
  }

  if (pendingCapabilities.isEmpty() && visibleNotes.isEmpty()) return

  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Set up Klardrop",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )

      pendingCapabilities.forEach { capability ->
        val status = state.capabilities[capability] ?: CapabilityStatus.Unknown
        CapabilityRow(
          capability = capability,
          status = status,
          onRequest = { onRequestCapability(capability) },
        )
      }

      visibleNotes.forEach { note ->
        EducationalNoteRow(
          note = note,
          onDismiss = { dismissedNotes = dismissedNotes + note.id },
        )
      }
    }
  }
}

@Composable
private fun CapabilityRow(
  capability: Capability,
  status: CapabilityStatus,
  onRequest: () -> Unit,
) {
  val (label, description) = capabilityCopy(capability)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = capabilityIcon(capability),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.size(24.dp),
    )
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
      )
    }
    TextButton(onClick = onRequest) {
      Text(
        text = if (status == CapabilityStatus.Denied) "Settings" else "Grant",
      )
    }
  }
}

@Composable
private fun EducationalNoteRow(
  note: EducationalNote,
  onDismiss: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = Icons.Default.Info,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
      modifier = Modifier.size(20.dp),
    )
    Text(
      text = note.message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
      modifier = Modifier.weight(1f),
    )
    Box(
      modifier = Modifier
        .size(20.dp)
        .clickable(onClick = onDismiss),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = "Dismiss note",
        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.size(16.dp),
      )
    }
  }
}

private fun capabilityCopy(capability: Capability): Pair<String, String> = when (capability) {
  Capability.LOCAL_NETWORK -> "Local network" to
    "Needed to find other devices on your Wi-Fi."
  Capability.BLUETOOTH -> "Bluetooth" to
    "Used as a fallback transport when Wi-Fi reachability isn't enough."
  Capability.NOTIFICATIONS -> "Notifications" to
    "Lets Klardrop alert you when a transfer arrives."
  Capability.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices" to
    "Required on Android 13+ to discover peers without sharing your location."
  Capability.LOCATION -> "Location" to
    "Required on older Android versions to scan for nearby Bluetooth devices."
}

private fun capabilityIcon(capability: Capability): ImageVector = when (capability) {
  Capability.LOCAL_NETWORK,
  Capability.NEARBY_WIFI_DEVICES -> Icons.Default.Wifi
  Capability.BLUETOOTH -> Icons.AutoMirrored.Filled.BluetoothSearching
  Capability.NOTIFICATIONS -> Icons.Default.Notifications
  Capability.LOCATION -> Icons.Default.LocationOn
}
