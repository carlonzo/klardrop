package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.chat.DeviceChatMode
import com.carlom.klardrop.chat.DeviceChatScreen
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.trust.TrustActionButton
import com.carlom.klardrop.trust.TrustBadge

private val SidebarWidth = 300.dp

@Composable
fun WideLayout(
  modifier: Modifier = Modifier,
  discoveryController: DiscoveryController,
  uiDependencies: UiDependencies
) {
  val state by discoveryController.screenStateFlow.collectAsState()
  var activeDeviceId by remember { mutableStateOf<String?>(null) }
  var pendingLink by remember { mutableStateOf<DeviceUi?>(null) }

  // Keep the controller aware of which chat is currently open so unread-badge
  // bookkeeping suppresses badges for that device.
  androidx.compose.runtime.LaunchedEffect(activeDeviceId) {
    discoveryController.setActiveChatDeviceId(activeDeviceId)
  }

  Row(modifier = modifier.fillMaxSize()) {

    WideSidebar(
      modifier = Modifier
        .width(SidebarWidth)
        .fillMaxSize(),
      currentDeviceName = state.currentDeviceName ?: state.systemDeviceName ?: "",
      devices = state.devices,
      activeDeviceId = activeDeviceId,
      onDeviceSelected = {
        discoveryController.onDeviceClick(it)
        activeDeviceId = it.deviceId
      },
      onRequestTrust = { pendingLink = it },
      onRemoveTrust = { discoveryController.onRemoveTrust(it) }
    )

    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    ) {

      IncomingBannerStack(
        state = state,
        callbacks = discoveryController
      )

      Box(modifier = Modifier.fillMaxSize()) {
        val currentId = activeDeviceId
        val activeDevice = currentId?.let { id -> state.devices.firstOrNull { it.deviceId == id } }
        if (currentId != null && activeDevice != null) {
          val chatViewModel = remember(currentId) {
            uiDependencies.deviceChatViewModelFactory(currentId)
          }
          DeviceChatScreen(
            deviceName = activeDevice.deviceName,
            isOwned = activeDevice.trustStatus == TrustStatus.Trusted,
            viewModel = chatViewModel,
            onBackClicked = { activeDeviceId = null },
            onOpenFileRequest = { path -> chatViewModel.openFileClicked(path) },
            mode = DeviceChatMode.Pane
          )
        } else {
          OverviewEmptyPane()
        }
      }
    }
  }

  pendingLink?.let { device ->
    LinkDeviceConfirmDialog(
      device = device,
      onConfirm = {
        discoveryController.onAddToTrusted(device)
        pendingLink = null
      },
      onDismiss = { pendingLink = null }
    )
  }

  state.pairingDialogState?.let { pairingState ->
    PairingApprovalDialog(
      state = pairingState,
      onDismiss = { discoveryController.dismissPairingDialog() }
    )
  }
}

@Composable
private fun WideSidebar(
  modifier: Modifier = Modifier,
  currentDeviceName: String,
  devices: List<DeviceUi>,
  activeDeviceId: String?,
  onDeviceSelected: (DeviceUi) -> Unit,
  onRequestTrust: (DeviceUi) -> Unit,
  onRemoveTrust: (DeviceUi) -> Unit
) {
  val trusted = devices.filter { it.trustStatus == TrustStatus.Trusted }
  val others = devices.filter { it.trustStatus != TrustStatus.Trusted }

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = modifier
  ) {
    Column(modifier = Modifier.fillMaxSize()) {

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Text(
          text = "Klardrop",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
          fontWeight = FontWeight.SemiBold
        )
        if (currentDeviceName.isNotBlank()) {
          Text(
            text = currentDeviceName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
      ) {
        if (trusted.isNotEmpty()) {
          SidebarSectionLabel("Your devices")
          trusted.forEach { device ->
            SidebarDeviceRow(
              device = device,
              isActive = device.deviceId == activeDeviceId,
              onClick = { onDeviceSelected(device) },
              onRequestTrust = { onRequestTrust(device) },
              onRemoveTrust = { onRemoveTrust(device) }
            )
          }
        }

        SidebarSectionLabel("Nearby")
        if (others.isEmpty()) {
          SidebarScanningPlaceholder()
        } else {
          others.forEach { device ->
            SidebarDeviceRow(
              device = device,
              isActive = device.deviceId == activeDeviceId,
              onClick = { onDeviceSelected(device) },
              onRequestTrust = { onRequestTrust(device) },
              onRemoveTrust = { onRemoveTrust(device) }
            )
          }
        }

        Spacer(Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun SidebarSectionLabel(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
  )
}

@Composable
private fun SidebarDeviceRow(
  device: DeviceUi,
  isActive: Boolean,
  onClick: () -> Unit,
  onRequestTrust: () -> Unit,
  onRemoveTrust: () -> Unit
) {
  val rowColor = if (isActive) {
    MaterialTheme.colorScheme.primary
  } else {
    androidx.compose.ui.graphics.Color.Transparent
  }
  val textColor = if (isActive) {
    MaterialTheme.colorScheme.onPrimary
  } else {
    MaterialTheme.colorScheme.onSurface
  }
  val secondaryColor = if (isActive) {
    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
  } else {
    MaterialTheme.colorScheme.onSurfaceVariant
  }

  Surface(
    color = rowColor,
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

      Box(contentAlignment = Alignment.Center) {
        val avatarBg = if (isActive) {
          MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
        } else {
          MaterialTheme.colorScheme.primaryContainer
        }
        val avatarTint = if (isActive) {
          MaterialTheme.colorScheme.onPrimary
        } else {
          MaterialTheme.colorScheme.onPrimaryContainer
        }
        Box(
          modifier = Modifier
            .size(36.dp)
            .background(avatarBg, CircleShape),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = deviceIcon(device.deviceType),
            contentDescription = null,
            tint = avatarTint,
            modifier = Modifier.size(18.dp)
          )
        }

        if (device.trustStatus == TrustStatus.Trusted && !isActive) {
          TrustBadge(modifier = Modifier.align(Alignment.TopEnd))
        }
        if (device.hasUnreadMessages) {
          Box(
            modifier = Modifier
              .align(Alignment.TopStart)
              .size(12.dp)
              .background(
                if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surfaceVariant,
                CircleShape
              )
              .padding(2.dp)
              .background(MaterialTheme.colorScheme.error, CircleShape)
          )
        }
      }

      Column(
        modifier = Modifier
          .weight(1f)
          .widthIn(max = 200.dp)
      ) {
        Text(
          text = device.deviceName,
          style = MaterialTheme.typography.titleSmall,
          color = textColor,
          fontWeight = if (device.hasUnreadMessages && !isActive) FontWeight.SemiBold else FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = deviceSubtitle(device),
          style = MaterialTheme.typography.labelSmall,
          color = secondaryColor,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      when (device.trustStatus) {
        TrustStatus.Untrusted, TrustStatus.Pairing -> {
          TrustActionButton(
            isTrusted = false,
            isLoading = device.trustStatus == TrustStatus.Pairing,
            onAddToTrusted = onRequestTrust,
            onRemoveTrust = onRemoveTrust,
            modifier = Modifier.size(32.dp)
          )
        }
        else -> Unit
      }
    }
  }
}

private fun deviceIcon(type: DeviceType): ImageVector = when (type) {
  DeviceType.MOBILE -> Icons.Filled.Smartphone
  DeviceType.DESKTOP -> Icons.Filled.LaptopMac
  else -> Icons.Filled.Devices
}

private fun deviceSubtitle(device: DeviceUi): String = when (device.trustStatus) {
  TrustStatus.Trusted -> "Trusted"
  TrustStatus.Pairing -> "Pairing…"
  TrustStatus.Untrusted -> "Nearby"
  TrustStatus.Unknown -> "Nearby"
}

@Composable
private fun SidebarScanningPlaceholder() {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Icon(
      imageVector = Icons.Filled.Wifi,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(14.dp)
    )
    Text(
      text = "Scanning…",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun OverviewEmptyPane(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .padding(32.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier.widthIn(max = 520.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

      Box(
        modifier = Modifier
          .size(72.dp)
          .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Filled.Devices,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(36.dp)
        )
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Text(
          text = "Pick a device to start",
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center
        )
        Text(
          text = "Select any device from the sidebar to open its chat and share text or files.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center
        )
      }

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        OnboardingRow(
          icon = Icons.Filled.Person,
          title = "Your devices",
          body = "Pair devices you own from the Nearby list to make them trusted. Trusted devices auto-share clipboard, Wi-Fi logins and notifications."
        )
        OnboardingRow(
          icon = Icons.Filled.Lock,
          title = "Nearby devices",
          body = "Other devices on your Wi-Fi show up under Nearby. They can send you files or text only after you accept each transfer."
        )
      }
    }
  }
}

@Composable
private fun OnboardingRow(
  icon: ImageVector,
  title: String,
  body: String
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(18.dp)
        )
      }
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = body,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

