@file:Suppress("DEPRECATION")

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.DisposableEffect
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
import com.carlom.klardrop.debug.DebugControl
import kotlinx.coroutines.launch
import com.carlom.klardrop.common.KlardropVersion
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.desktop.SingleInstance
import com.carlom.klardrop.theme.AppTheme
import com.klardrop.common.initCrashReporter
import io.github.vinceglb.filekit.FileKit
import java.awt.EventQueue
import java.awt.Taskbar
import java.awt.Window as AwtWindow
import javax.imageio.ImageIO
import kotlin.system.exitProcess

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
  val dataDir = args.firstOrNull { it.startsWith("--data-dir=") }?.substringAfter("=")
    ?: System.getenv("KLARDROP_HOME")
  val controlPort = args.firstOrNull { it.startsWith("--control-port=") }?.substringAfter("=")?.toIntOrNull()
    ?: if (debug) 8765 else null

  var enableKlardrop = !args.contains("--no-klardrop")
  var enableNearby = !args.contains("--no-nearby")
  var enableBle = !args.contains("--no-ble")
  when {
    args.contains("--klardrop-only") -> {
      enableKlardrop = true
      enableNearby = false
      enableBle = false
    }
    args.contains("--nearby-only") -> {
      enableKlardrop = false
      enableNearby = true
      enableBle = false
    }
    args.contains("--ble-only") -> {
      enableKlardrop = false
      enableNearby = false
      enableBle = true
    }
  }

  if (dataDir != null) {
    System.setProperty("klardrop.data.dir", dataDir)
  }

  val applicationInfo = ApplicationInfo(
    isDebug = debug,
    disablePersistence = inMemory,
    enableKlardropServer = enableKlardrop,
    enableNearbyServer = enableNearby,
    enableBle = enableBle,
    controlPort = controlPort,
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

  val filesToSend = parseSendFiles(args)

  // Single-instance guard: before touching any app state, make sure no other instance
  // owns the data dir. A second launch focuses the first window (and forwards any files to send) and exits.
  val instanceGuard = if (dataDir != null) {
    SingleInstance.acquire(java.io.File(dataDir), filesToSend)
  } else {
    SingleInstance.acquire(sendFiles = filesToSend)
  }
  if (instanceGuard == null) {
    println("Klardrop is already running — passed request to existing instance")
    exitProcess(0)
  }

  if (dataDir != null) {
    val filesDir = java.io.File(dataDir)
    val cacheDir = java.io.File(dataDir, "cache")
    filesDir.mkdirs()
    cacheDir.mkdirs()
    FileKit.init("klardrop", filesDir, cacheDir)
  } else {
    FileKit.init("klardrop")
  }

  val k = Klardrop(
    applicationInfo = applicationInfo,
    internalPlatformDependency = InternalPlatformDependencies(applicationInfo)
  )
  k.init()

  if (applicationInfo.isDebug && applicationInfo.controlPort != null) {
    k.commonComponent.coroutines().appScope.launch {
      DebugControl.start(k)
    }
  }

  application {

    val windowState = rememberWindowState()
    var isWindowVisible by remember { mutableStateOf(true) }
    var activeWindow by remember { mutableStateOf<AwtWindow?>(null) }
    var pendingShareFiles by remember { mutableStateOf<List<String>?>(filesToSend.ifEmpty { null }) }

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

    // Connect DebugControl programmatic window visibility endpoints
    LaunchedEffect(Unit) {
      DebugControl.windowVisibilityProvider = { isWindowVisible }
      DebugControl.windowVisibilitySetter = { visible ->
        EventQueue.invokeLater {
          isWindowVisible = visible
        }
      }
    }

    // A second launch asked us to come forward: raise and focus the window on the
    // UI thread (the focus server thread itself must not touch AWT).
    // Wired at the application level so requests are handled whether the window is open or closed.
    LaunchedEffect(instanceGuard) {
      instanceGuard.onFocus = {
        EventQueue.invokeLater {
          isWindowVisible = true
          activeWindow?.toFront()
          activeWindow?.requestFocusInWindow()
        }
      }
      instanceGuard.onSendFiles = { files ->
        EventQueue.invokeLater {
          pendingShareFiles = files
          isWindowVisible = true
          activeWindow?.toFront()
          activeWindow?.requestFocusInWindow()
        }
      }
    }

    // When the window is closed, it is completely torn down and removed from composition.
    // Explicitly call System.gc() after disposal to immediately signal the JVM to release
    // unreferenced UI objects, close Skiko rendering surfaces, and uncommit memory.
    LaunchedEffect(isWindowVisible) {
      if (!isWindowVisible) {
        EventQueue.invokeLater {
          System.gc()
        }
      }
    }

    if (trayAvailable) {
      KlardropTray(
        peers = peers,
        isWindowVisible = isWindowVisible,
        updateLabel = updateLabel,
        onToggleWindow = { isWindowVisible = !isWindowVisible },
        onShowWindow = {
          isWindowVisible = true
          activeWindow?.toFront()
          activeWindow?.requestFocusInWindow()
        },
      )
    }

    if (isWindowVisible) {
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
        resizable = true,
        state = windowState
      ) {

        DisposableEffect(window) {
          activeWindow = window
          onDispose {
            activeWindow = null
          }
        }

        LaunchedEffect(window) {
          window.toFront()
          window.requestFocusInWindow()

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
          KlardropApp(
            k,
            contentInsets = chromeInsets,
            isDesktop = true,
            pendingFiles = pendingShareFiles,
            onClearPendingFiles = { pendingShareFiles = null },
          )
        }

      }
    }
  }

}

private fun parseSendFiles(args: Array<String>): List<String> {
  val nonFlags = args.filter { !it.startsWith("--") && !it.startsWith("-D") }
  val candidates = if (nonFlags.firstOrNull() == "send") {
    nonFlags.drop(1)
  } else {
    nonFlags
  }
  return candidates.map { java.io.File(it).absolutePath }
}
