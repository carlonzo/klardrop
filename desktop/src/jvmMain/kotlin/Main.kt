import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.carlom.klardrop.FilePickerFactory
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import org.jetbrains.skia.Image
import java.io.FileInputStream

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

    val bytesImage = this::class.java.classLoader.getResourceAsStream("icon_launcher.png")!!.use { it.buffered().readAllBytes() }
    val image = Image.makeFromEncoded(bytesImage).toComposeImageBitmap()
    val windowState = rememberWindowState()

    Window(
      title = "Klardrop",
      onCloseRequest = ::exitApplication,
      resizable = true,
      icon = BitmapPainter(image),
      state = windowState
    ) {

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
