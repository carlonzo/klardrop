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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.common.KlardropVersion
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import com.klardrop.common.initCrashReporter
import io.github.vinceglb.filekit.FileKit
import java.awt.Taskbar
import java.awt.Window as AwtWindow
import javax.imageio.ImageIO

// Height of the macOS title-bar inset (points). Sized just tall enough to
// clear the default macOS traffic-light cluster (~28 pt) with a hair of
// margin below — the sidebar pane starts at this y, so anything bigger
// leaves an awkward gap of empty window background above the pane.
private const val MAC_TITLE_BAR_HEIGHT = 36f

/**
 * Ask the JetBrains Runtime (the JDK that ships with Compose Desktop) to give
 * this AWT window a taller custom title bar — the OS then re-centers the
 * traffic-light buttons inside that taller bar, so they appear visually
 * "moved down". Quietly does nothing on a non-JBR JDK.
 */
private fun applyMacCustomTitleBar(window: AwtWindow, heightPx: Float) {
    runCatching {
        val jbr = Class.forName("com.jetbrains.JBR")
        val decorations = jbr.getMethod("getWindowDecorations").invoke(null) ?: return
        val titleBar = decorations.javaClass.getMethod("createCustomTitleBar").invoke(decorations)
        titleBar.javaClass
            .getMethod("setHeight", Float::class.javaPrimitiveType)
            .invoke(titleBar, heightPx)
        val setCustom = decorations.javaClass.methods
            .firstOrNull { it.name == "setCustomTitleBar" && it.parameterCount == 2 }
            ?: return
        setCustom.invoke(decorations, window, titleBar)
    }
}

fun main(args: Array<String>) {

  // Linux/X11: the JVM turns the GDK_SCALE env var into an AWT UI scale. On a
  // 4K display whose compositor isn't scaling (e.g. a Hyprland monitor at
  // scale 1.0), an inherited GDK_SCALE=2 doubles every dp and the window comes
  // out twice the intended size. Pin the UI scale to 1 unless the user has
  // explicitly overridden it with -Dsun.java2d.uiScale. Must run before any
  // AWT class initializes, since the scale is read during toolkit startup.
  // macOS and Windows resolve HiDPI through their own paths, so leave them be.
  if (System.getProperty("os.name").lowercase().contains("linux") &&
    System.getProperty("sun.java2d.uiScale") == null
  ) {
    System.setProperty("sun.java2d.uiScale", "1")
  }

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

  initCrashReporter(
    appVersion = applicationInfo.appVersion,
    isProduction = !applicationInfo.isDebug,
  )

  // macOS: use the system's full-window-content / transparent-title-bar mode.
  // Combined with title="" and rootPane client properties below, this drops the
  // title text and the title-bar border, leaving the traffic-light buttons
  // floating over the dark content.
  if (System.getProperty("os.name").lowercase().contains("mac")) {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.awt.application.name", KlardropVersion.APP_NAME)
  }

  // Set the dock / taskbar / Alt-Tab icon at runtime. The bundler-supplied
  // .icns covers a packaged .app, but a plain `./gradlew :desktop:run` bypasses
  // the bundler — without this the JVM falls back to the generic Java cup.
  runCatching {
    val iconStream = {}::class.java.getResourceAsStream("/icons/app-icon-1024.png")
    val image = iconStream?.use { ImageIO.read(it) }
    if (image != null && Taskbar.isTaskbarSupported()) {
      val taskbar = Taskbar.getTaskbar()
      if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        taskbar.iconImage = image
      }
    }
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

    val trayAvailable = remember { klardropTrayAvailable() }
    val visibleDevices by k.visibleDevices().visibleDevices.collectAsState()
    val trustedDevices by k.trustedDevices().collectAsState()
    val peers = remember(visibleDevices, trustedDevices) {
      trayPeers(visibleDevices, trustedDevices)
    }

    val isMacOs = remember { System.getProperty("os.name").lowercase().contains("mac") }

    val updateStatus by k.updateStatus().collectAsState()
    val updateInstall by k.updateInstallProgress().collectAsState()
    val updateLabel = remember(updateStatus, updateInstall) {
      trayUpdateLabel(updateStatus, updateInstall)
    }

    if (trayAvailable) {
      KlardropTray(
        peers = peers,
        isWindowVisible = isWindowVisible,
        updateLabel = updateLabel,
        onToggleWindow = { isWindowVisible = !isWindowVisible },
        onShowWindow = { isWindowVisible = true },
      )
    }

    Window(
      title = "",
      icon = painterResource("icons/app-icon.svg"),
      onCloseRequest = {
        if (trayAvailable) {
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
        if (isMacOs) {
          // Push the traffic lights down into a taller, Xcode-style title
          // bar. JBR-only — silently no-ops on stock OpenJDK.
          applyMacCustomTitleBar(window, MAC_TITLE_BAR_HEIGHT)
        }
      }

      val chromeInsets = if (isMacOs)
        PaddingValues(top = MAC_TITLE_BAR_HEIGHT.dp)
      else
        PaddingValues(0.dp)

      AppTheme {

        KlardropApp(k, contentInsets = chromeInsets, isDesktop = true)
      }

    }
  }

}
