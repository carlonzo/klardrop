import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.carlom.klardrop.FilePickerFactory
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme

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

  val k = Klardrop(
    applicationInfo = applicationInfo,
    internalPlatformDependency = InternalPlatformDependencies()
  )
  k.init()

  application {

    Window(
      title = "Klardrop",
      onCloseRequest = ::exitApplication,
      resizable = true,
    ) {
//      val state = rememberWindowState(width = 800.dp, height = 600.dp)

      val uiDependencies = remember(window) {
        object : UiDependencies {
          override fun filePickerFactory(): FilePickerFactory {
            return FilePickerFactory(window)
          }

        }
      }

      AppTheme {

        KlardropApp(k, uiDependencies)
      }

    }
  }

}
