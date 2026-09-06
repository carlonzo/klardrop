import com.carlom.klardrop.common.KlardropVersion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray as AwtTray
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.DiscoveryDevice
import com.carlom.klardrop.common.update.InstallProgress
import com.carlom.klardrop.common.update.UpdateStatus
import dev.nucleusframework.composenativetray.tray.api.Tray as NativeTray
import java.awt.SystemTray

/**
 * True when this process can keep a tray icon. Linux always can: we speak
 * StatusNotifierItem ourselves, so AWT's `SystemTray.isSupported()` (false on
 * Wayland/Hyprland) does not matter. Other OSes keep the Compose Desktop tray.
 */
internal fun klardropTrayAvailable(): Boolean {
  val os = System.getProperty("os.name").orEmpty().lowercase()
  return os.contains("linux") || SystemTray.isSupported()
}

internal data class TrayPeer(
  val id: String,
  val name: String,
  val online: Boolean,
) {
  val menuLabel: String get() = if (online) name else "$name (offline)"
}

/** Same roster the desktop window uses: currently visible peers, plus paired ones that are offline. */
internal fun trayPeers(
  visible: Map<String, DiscoveryDevice>,
  trusted: Map<String, DeviceInfo>,
): List<TrayPeer> {
  val nearby = visible.values
    .sortedBy { it.deviceInfo.name.lowercase() }
    .map { TrayPeer(it.deviceInfo.deviceId, it.deviceInfo.name, online = true) }
  val seen = nearby.map { it.id }.toSet()
  val offline = trusted.values
    .filter { it.deviceId !in seen }
    .sortedBy { it.name.lowercase() }
    .map { TrayPeer(it.deviceId, it.name, online = false) }
  return nearby + offline
}

@Composable
internal fun ApplicationScope.KlardropTray(
  peers: List<TrayPeer>,
  isWindowVisible: Boolean,
  updateLabel: String?,
  onToggleWindow: () -> Unit,
  onShowWindow: () -> Unit,
) {
  val os = System.getProperty("os.name").orEmpty().lowercase()
  if (os.contains("linux")) {
    LinuxNativeTray(
      peers = peers,
      isWindowVisible = isWindowVisible,
      updateLabel = updateLabel,
      onToggleWindow = onToggleWindow,
      onShowWindow = onShowWindow,
      onQuit = ::exitApplication,
    )
  } else if (SystemTray.isSupported()) {
    AwtPlatformTray(
      peers = peers,
      isWindowVisible = isWindowVisible,
      updateLabel = updateLabel,
      onToggleWindow = onToggleWindow,
      onShowWindow = onShowWindow,
      onQuit = ::exitApplication,
    )
  }
}

/**
 * Tray entry for a pending update, or null when there is nothing to say. The tray is
 * the only Klardrop surface a user sees while the window is hidden — which, for a
 * share target that lives in the background, is most of the time — so an update that
 * only ever announced itself in the window could go unnoticed for weeks. Clicking it
 * opens the window, where the banner and the Updates settings do the actual work.
 */
internal fun trayUpdateLabel(status: UpdateStatus, install: InstallProgress): String? = when {
  install is InstallProgress.Ready -> "Restart to update"
  status is UpdateStatus.Available -> "Update available — ${status.version}"
  else -> null
}

@Composable
private fun LinuxNativeTray(
  peers: List<TrayPeer>,
  isWindowVisible: Boolean,
  updateLabel: String?,
  onToggleWindow: () -> Unit,
  onShowWindow: () -> Unit,
  onQuit: () -> Unit,
) {
  // Keep a single StatusNotifierItem. Remounting it (e.g. via key()) keeps
  // the same SNI id, so Omarchy's QsMenuOpener stays bound to the first empty
  // DBusMenu. NativeTray hashes this builder and calls update() in place.
  val icon = painterResource("icons/menubar.png")
  NativeTray(
    icon = icon,
    tooltip = trayTooltip(peers),
    primaryAction = onShowWindow,
  ) {
    Item(label = if (isWindowVisible) "Hide ${KlardropVersion.APP_NAME}" else "Show ${KlardropVersion.APP_NAME}") {
      onToggleWindow()
    }
    if (updateLabel != null) {
      Item(label = updateLabel) {
        onShowWindow()
      }
    }
    Divider()
    if (peers.isEmpty()) {
      Item(label = "No devices found", isEnabled = false)
    } else {
      peers.forEach { peer ->
        Item(label = peer.menuLabel) {
          onShowWindow()
        }
      }
    }
    Divider()
    Item(label = "Quit ${KlardropVersion.APP_NAME}") {
      dispose()
      onQuit()
    }
  }
}

private fun trayTooltip(peers: List<TrayPeer>): String {
  val online = peers.count { it.online }
  return when {
    peers.isEmpty() -> KlardropVersion.APP_NAME
    online == 0 -> "${KlardropVersion.APP_NAME} · ${peers.size} offline"
    online == peers.size -> "${KlardropVersion.APP_NAME} · $online nearby"
    else -> "${KlardropVersion.APP_NAME} · $online nearby, ${peers.size - online} offline"
  }
}

@Composable
private fun ApplicationScope.AwtPlatformTray(
  peers: List<TrayPeer>,
  isWindowVisible: Boolean,
  updateLabel: String?,
  onToggleWindow: () -> Unit,
  onShowWindow: () -> Unit,
  onQuit: () -> Unit,
) {
  val isMacOs = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
  val trayIcon = if (isMacOs) "icons/menubar.svg" else "icons/app-icon.svg"
  AwtTray(
    icon = painterResource(trayIcon),
    tooltip = trayTooltip(peers),
    onAction = onShowWindow,
    menu = {
      Item(
        text = if (isWindowVisible) "Hide ${KlardropVersion.APP_NAME}" else "Show ${KlardropVersion.APP_NAME}",
        onClick = onToggleWindow,
      )
      if (updateLabel != null) {
        Item(text = updateLabel, onClick = onShowWindow)
      }
      Separator()
      if (peers.isEmpty()) {
        Item(text = "No devices found", enabled = false, onClick = {})
      } else {
        peers.forEach { peer ->
          Item(
            text = peer.menuLabel,
            onClick = onShowWindow,
          )
        }
      }
      Separator()
      Item(text = "Quit ${KlardropVersion.APP_NAME}", onClick = onQuit)
    },
  )
}
