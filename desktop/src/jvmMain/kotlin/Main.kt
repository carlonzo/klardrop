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
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
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

  val k = Klardrop(
    applicationInfo = applicationInfo,
    internalPlatformDependency = InternalPlatformDependencies()
  )
  k.init()

  FileKit.init("klardrop")

  application {

    val windowState = rememberWindowState()

    Window(
      title = "Klardrop",
      onCloseRequest = ::exitApplication,
      resizable = true,
      state = windowState
    ) {


      AppTheme {

        KlardropApp(k)
      }

    }
  }

}
