import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.carlom.klardrop.theme.KdTheme
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File
import java.util.Properties

/** Where Klardrop shows itself on macOS while running. */
internal enum class MacAppIconMode(val label: String) {
  MenuBarOnly("Menu bar only"),
  DockOnly("Dock only"),
  Both("Menu bar and Dock"),
  ;

  val showsDockIcon: Boolean get() = this != MenuBarOnly
  val showsMenuBarIcon: Boolean get() = this != DockOnly
}

/**
 * Persists [MacAppIconMode] in a properties file under the app's data dir. It is
 * read before AWT starts (to decide `apple.awt.UIElement`), which is too early for
 * the DataStore-backed repositories in common.
 */
internal object MacAppIconSettings {
  private const val KEY = "mac_app_icon_mode"

  private fun file(): File {
    val dataDir = System.getProperty("klardrop.data.dir") ?: System.getenv("KLARDROP_HOME")
    val dir = if (dataDir != null) File(dataDir) else File(System.getProperty("user.home"), ".klardrop")
    return File(dir, "desktop.properties")
  }

  fun load(): MacAppIconMode = runCatching {
    val props = Properties()
    file().takeIf { it.isFile }?.inputStream()?.use(props::load)
    props.getProperty(KEY)?.let(MacAppIconMode::valueOf)
  }.getOrNull() ?: MacAppIconMode.Both

  fun save(mode: MacAppIconMode) {
    runCatching {
      val f = file()
      f.parentFile.mkdirs()
      val props = Properties()
      if (f.isFile) f.inputStream().use(props::load)
      props.setProperty(KEY, mode.name)
      f.outputStream().use { props.store(it, null) }
    }
  }
}

/**
 * Toggles the dock icon at runtime via `-[NSApplication setActivationPolicy:]`.
 * AppKit must be driven from the main thread, which is not the AWT event thread,
 * so the call is dispatched onto the main queue.
 */
internal object MacDock {
  private const val POLICY_REGULAR = 0L
  private const val POLICY_ACCESSORY = 1L

  @Suppress("FunctionName")
  private interface ObjC : Library {
    fun objc_getClass(name: String): Pointer?
    fun sel_registerName(name: String): Pointer
    fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
    fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Long): Pointer?
  }

  @Suppress("FunctionName")
  private interface LibDispatch : Library {
    fun dispatch_async_f(queue: Pointer, context: Pointer?, work: Work)
  }

  private fun interface Work : Callback {
    fun invoke(context: Pointer?)
  }

  private val objc by lazy { Native.load("objc", ObjC::class.java) }
  private val dispatch by lazy { Native.load("System", LibDispatch::class.java) }
  private val mainQueue by lazy { NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q") }

  // JNA callbacks must stay strongly reachable until the native side has run them.
  private val pending = mutableSetOf<Work>()

  private fun onMainThread(block: () -> Unit) {
    lateinit var work: Work
    work = Work {
      try {
        block()
      } finally {
        synchronized(pending) { pending.remove(work) }
      }
    }
    synchronized(pending) { pending.add(work) }
    dispatch.dispatch_async_f(mainQueue, null, work)
  }

  private fun nsApp(): Pointer? {
    val cls = objc.objc_getClass("NSApplication") ?: return null
    return objc.objc_msgSend(cls, objc.sel_registerName("sharedApplication"))
  }

  fun setDockIconVisible(visible: Boolean) {
    runCatching {
      onMainThread {
        val app = nsApp() ?: return@onMainThread
        objc.objc_msgSend(
          app,
          objc.sel_registerName("setActivationPolicy:"),
          if (visible) POLICY_REGULAR else POLICY_ACCESSORY,
        )
        // Changing policy deactivates the app; bring it back so the window stays in front.
        objc.objc_msgSend(app, objc.sel_registerName("activateIgnoringOtherApps:"), 1L)
      }
    }
  }

  /** Brings the app forward; needed when it has no dock icon, since `toFront()` alone doesn't activate it. */
  fun activate() {
    runCatching {
      onMainThread {
        val app = nsApp() ?: return@onMainThread
        objc.objc_msgSend(app, objc.sel_registerName("activateIgnoringOtherApps:"), 1L)
      }
    }
  }
}

@Composable
internal fun MacAppIconSettingsSection(
  mode: MacAppIconMode,
  onModeChange: (MacAppIconMode) -> Unit,
) {
  val colors = KdTheme.colors
  val typography = KdTheme.typography
  val spacing = KdTheme.spacing

  Column(modifier = Modifier.fillMaxWidth()) {
    Text(text = "Show Klardrop in", style = typography.body.copy(color = colors.text))
    Spacer(Modifier.height(spacing.s1))
    MacAppIconMode.entries.forEach { option ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onModeChange(option) }
          .padding(vertical = spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(selected = option == mode, onClick = { onModeChange(option) })
        Text(text = option.label, style = typography.body.copy(color = colors.text))
      }
    }
  }
}
