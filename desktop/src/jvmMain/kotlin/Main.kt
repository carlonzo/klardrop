@file:Suppress("DEPRECATION")

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import com.klardrop.common.BugsnagWrapper
import io.github.vinceglb.filekit.FileKit
import java.awt.SystemTray

fun main(args: Array<String>) {

  println("Args: ${args.joinToString(", ")}")

  val debug = args.contains("--debug")
  val inMemory = args.contains("--no-persistence")
  val disableKlardrop = args.contains("--no-klardrop")
  val disableNearby = args.contains("--no-nearby")

  val applicationInfo = ApplicationInfo(

    isDebug = debug,
    disablePersistence = inMemory,
    enableKlardropServer = !disableKlardrop,
    enableNearbyServer = !disableNearby,
  )

  BugsnagWrapper.init(
    applicationInfo.appVersion
  )

  // macOS: use the system's full-window-content / transparent-title-bar mode.
  // Combined with title="" and rootPane client properties below, this drops the
  // title text and the title-bar border, leaving the traffic-light buttons
  // floating over the dark content.
  if (System.getProperty("os.name").lowercase().contains("mac")) {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", "Klardrop")
  }

  FileKit.init("klardrop")

  val k = Klardrop(
    applicationInfo = applicationInfo,
    internalPlatformDependency = InternalPlatformDependencies(applicationInfo)
  )
  k.init()


  application {

    val windowState = rememberWindowState()
    var isWindowVisible by remember { mutableStateOf(true) }

    val traySupported = remember { SystemTray.isSupported() }
    val devices by k.visibleDevices().visibleDevices.collectAsState()

    if (traySupported) {
      Tray(
        icon = painterResource("icon_launcher.png"),
        tooltip = "Klardrop",
        onAction = { isWindowVisible = true },
        menu = {
          Item(
            text = if (isWindowVisible) "Hide Klardrop" else "Show Klardrop",
            onClick = { isWindowVisible = !isWindowVisible }
          )
          Separator()
          if (devices.isEmpty()) {
            Item(text = "No devices found", enabled = false, onClick = {})
          } else {
            devices.values
              .sortedBy { it.deviceInfo.name.lowercase() }
              .forEach { device ->
                Item(
                  text = device.deviceInfo.name,
                  onClick = { isWindowVisible = true }
                )
              }
          }
          Separator()
          Item(text = "Quit Klardrop", onClick = ::exitApplication)
        }
      )
    }

    Window(
      title = "",
      onCloseRequest = {
        if (traySupported) {
          isWindowVisible = false
        } else {
          exitApplication()
        }
      },
      visible = isWindowVisible,
      resizable = true,
      state = windowState
    ) {

      LaunchedEffect(window) {
        // On macOS this hides the title-bar border + title text and lets the
        // dark content extend under the chrome, keeping only the traffic
        // lights visible. No-ops on other platforms.
        runCatching {
          window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
          window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
          window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
      }

      val isMac = remember { System.getProperty("os.name").lowercase().contains("mac") }
      val chromeInsets = if (isMac) PaddingValues(top = 28.dp) else PaddingValues(0.dp)

      AppTheme {

        KlardropApp(k, contentInsets = chromeInsets)
      }

    }
  }

}
