import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.onExternalDrag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.carlom.klardrop.DiscoveryDashboard
import com.carlom.klardrop.ShowVisibleDevicesController
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme
import com.carlom.klardrop.theme.md_theme_light_background
import java.awt.FileDialog

fun main() {
  val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies())
  k.init()

  val discoveryController = ShowVisibleDevicesController(k.commonComponent)


  application {



    Window(
      title = "Klardrop",
      onCloseRequest = ::exitApplication,
      resizable = true,
    ) {
      val state = rememberWindowState(width = 800.dp, height = 600.dp)

      AppTheme {

        Surface(
          modifier = Modifier.fillMaxSize(),
        ) {

          Row(horizontalArrangement = Arrangement.SpaceBetween) {

            Text("Hello in Klardrop")

//            val minPanelWidth = (state.size.width / 3) * 2
//            val panelWidth = if (state.size.width > minPanelWidth) minPanelWidth else state.size.width

            DiscoveryDashboard(
              modifier = Modifier
                .fillMaxWidth(fraction = 0.75f)
                .fillMaxHeight(),
              showVisibleDevicesController = discoveryController
            )
          }


        }
      }


    }
  }

}
