package com.carlom.klardrop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.trust.model.TrustedDevice
import com.carlom.klardrop.utils.TimeFormatUtils
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.trust.model.Permission
import com.carlom.klardrop.common.trust.model.TrustLevel
import kotlinx.coroutines.flow.StateFlow

/**
 * Trust management screen showing trusted devices and trust settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustManagementScreen(
  trustedDevices: StateFlow<List<TrustedDevice>>,
  onRemoveDevice: (String) -> Unit,
  onUpdatePermissions: (String, Set<Permission>) -> Unit,
  onCreateTrustGroup: () -> Unit,
  onExportTrustData: () -> Unit,
  onImportTrustData: () -> Unit,
  onNavigateToSecurityLog: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  val devices by trustedDevices.collectAsState()

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Trust Management") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = onNavigateToSecurityLog) {
            Icon(Icons.Default.Warning, contentDescription = "Security Log")
          }
        }
      )
    },
    modifier = modifier
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Trust group section
      item {
        TrustGroupSection(
          hasGroup = devices.isNotEmpty(),
          onCreateGroup = onCreateTrustGroup,
          onExportData = onExportTrustData,
          onImportData = onImportTrustData
        )
      }

      // Trusted devices section
      if (devices.isNotEmpty()) {
        item {
          Text(
            text = "Trusted Devices",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
          )
        }

        items(devices) { device ->
          TrustedDeviceCard(
            device = device,
            onRemove = { onRemoveDevice(device.deviceId) },
            onUpdatePermissions = { permissions ->
              onUpdatePermissions(device.deviceId, permissions)
            }
          )
        }
      } else {
        item {
          EmptyTrustState(onCreateGroup = onCreateTrustGroup)
        }
      }
    }
  }
}

/**
 * Trust group section with management options
 */
@Composable
private fun TrustGroupSection(
  hasGroup: Boolean,
  onCreateGroup: () -> Unit,
  onExportData: () -> Unit,
  onImportData: () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp)
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Trust Group",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )

        if (!hasGroup) {
          Button(
            onClick = onCreateGroup,
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary
            )
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Group")
          }
        }
      }

      if (hasGroup) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedButton(
            onClick = onExportData,
            modifier = Modifier.weight(1f)
          ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Export")
          }

          OutlinedButton(
            onClick = onImportData,
            modifier = Modifier.weight(1f)
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Import")
          }
        }
      }
    }
  }
}

/**
 * Individual trusted device card
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrustedDeviceCard(
  device: TrustedDevice,
  onRemove: () -> Unit,
  onUpdatePermissions: (Set<Permission>) -> Unit
) {
  var showPermissionDialog by remember { mutableStateOf(false) }
  var showRemoveDialog by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp)
  ) {
    Column(
      modifier = Modifier.padding(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = device.deviceName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )

          Spacer(modifier = Modifier.height(4.dp))

          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            DeviceTypeChip(device.deviceType)
            TrustLevelChip(device.trustLevel)
          }

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = "Added ${TimeFormatUtils.formatRelativeTime(device.addedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          device.lastSeen?.let { lastSeen ->
            Text(
              text = "Last seen ${TimeFormatUtils.formatRelativeTime(lastSeen)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          device.expiresAt?.let { expiresAt ->
            Text(
              text = "Expires ${TimeFormatUtils.formatRelativeTime(expiresAt)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error
            )
          }
        }

        Row {
          IconButton(onClick = { showPermissionDialog = true }) {
            Icon(Icons.Default.Settings, contentDescription = "Permissions")
          }

          IconButton(onClick = { showRemoveDialog = true }) {
            Icon(
              Icons.Default.Delete,
              contentDescription = "Remove",
              tint = MaterialTheme.colorScheme.error
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Permission indicators
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        if (device.permissions.contains(Permission.FILE_SEND)) {
          PermissionChip("Send Files", Icons.Default.Send)
        }
        if (device.permissions.contains(Permission.FILE_RECEIVE)) {
          PermissionChip("Receive Files", Icons.Default.MoveToInbox)
        }
        if (device.permissions.contains(Permission.CLIPBOARD_SYNC)) {
          PermissionChip("Clipboard Sync", Icons.Default.ContentCopy)
        }
      }
    }
  }

  // Permission edit dialog
  if (showPermissionDialog) {
    PermissionEditDialog(
      currentPermissions = device.permissions,
      onDismiss = { showPermissionDialog = false },
      onConfirm = { permissions ->
        onUpdatePermissions(permissions)
        showPermissionDialog = false
      }
    )
  }

  // Remove confirmation dialog
  if (showRemoveDialog) {
    AlertDialog(
      onDismissRequest = { showRemoveDialog = false },
      title = { Text("Remove Device") },
      text = { Text("Are you sure you want to remove ${device.deviceName} from your trust group?") },
      confirmButton = {
        TextButton(
          onClick = {
            onRemove()
            showRemoveDialog = false
          },
          colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error
          )
        ) {
          Text("Remove")
        }
      },
      dismissButton = {
        TextButton(onClick = { showRemoveDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

/**
 * Device type chip
 */
@Composable
private fun DeviceTypeChip(deviceType: com.carlom.klardrop.common.utils.DeviceType) {

  val (icon, label) = when (deviceType) {
    DeviceType.MOBILE -> Icons.Default.PhoneAndroid to "Phone"
    DeviceType.DESKTOP -> Icons.Default.Computer to "Laptop"
    DeviceType.UNKNOWN -> Icons.Default.Devices to "Unknown"
  }

  AssistChip(
    onClick = { },
    label = { Text(label) },
    leadingIcon = {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(16.dp)
      )
    }
  )
}

/**
 * Trust level chip
 */
@Composable
private fun TrustLevelChip(trustLevel: TrustLevel) {
  val (label, color) = when (trustLevel) {
    TrustLevel.TRUSTED -> "Trusted" to MaterialTheme.colorScheme.primary
    TrustLevel.FULL -> "Full Trust" to MaterialTheme.colorScheme.primary
    TrustLevel.LIMITED -> "Limited" to MaterialTheme.colorScheme.secondary
    TrustLevel.MINIMAL -> "Minimal" to MaterialTheme.colorScheme.secondary
    TrustLevel.UNTRUSTED -> "Untrusted" to MaterialTheme.colorScheme.error
  }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = color.copy(alpha = 0.1f)
  ) {
    Text(
      text = label,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
      style = MaterialTheme.typography.labelSmall,
      color = color
    )
  }
}

/**
 * Permission chip
 */
@Composable
private fun PermissionChip(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.secondaryContainer
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSecondaryContainer
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer
      )
    }
  }
}

/**
 * Permission edit dialog
 */
@Composable
private fun PermissionEditDialog(
  currentPermissions: Set<Permission>,
  onDismiss: () -> Unit,
  onConfirm: (Set<Permission>) -> Unit
) {
  val permissions = remember {
    mutableStateMapOf<Permission, Boolean>().apply {
      put(
        Permission.FILE_SEND,
        currentPermissions.contains(Permission.FILE_SEND)
      )
      put(
        Permission.FILE_RECEIVE,
        currentPermissions.contains(Permission.FILE_RECEIVE)
      )
      put(
        Permission.CLIPBOARD_SYNC,
        currentPermissions.contains(Permission.CLIPBOARD_SYNC)
      )
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit Permissions") },
    text = {
      Column {
        permissions.forEach { (permission, enabled) ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                permissions[permission] = !enabled
              }
              .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = when (permission) {
                Permission.FILE_SEND -> "Send Files"
                Permission.FILE_RECEIVE -> "Receive Files"
                Permission.CLIPBOARD_SYNC -> "Clipboard Sync"
              }
            )

            Switch(
              checked = permissions[permission] ?: false,
              onCheckedChange = { permissions[permission] = it }
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val selectedPermissions = permissions
            .filter { it.value }
            .keys
            .toSet()
          onConfirm(selectedPermissions)
        }
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

/**
 * Empty state when no devices are trusted
 */
@Composable
private fun EmptyTrustState(onCreateGroup: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Icon(
      imageVector = Icons.Default.Lock,
      contentDescription = null,
      modifier = Modifier.size(64.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
      text = "No Trusted Devices",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold
    )

    Text(
      text = "Create a trust group to securely share files and sync clipboard with your other devices",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(onClick = onCreateGroup) {
      Icon(Icons.Default.Add, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text("Create Trust Group")
    }
  }
}

