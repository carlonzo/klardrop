import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.carlom.klardrop.ActionUi
import com.carlom.klardrop.DiscoveryDashboard
import com.carlom.klardrop.OnDataToSend
import com.carlom.klardrop.ShowVisibleDevicesController
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.features.openFileChooser
import com.carlom.klardrop.theme.AppTheme
import kotlinx.coroutines.launch

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
//      val state = rememberWindowState(width = 800.dp, height = 600.dp)

      AppTheme {

        Surface(
          modifier = Modifier.fillMaxSize(),
        ) {

          Row(horizontalArrangement = Arrangement.SpaceBetween) {

            Text("Hello in Klardrop")

//            val minPanelWidth = (state.fileSize.width / 3) * 2
//            val panelWidth = if (state.fileSize.width > minPanelWidth) minPanelWidth else state.fileSize.width

            DiscoveryDashboard(
              modifier = Modifier
                .fillMaxWidth(fraction = 0.75f)
                .fillMaxHeight(),
              showVisibleDevicesController = discoveryController
            )
          }


        }
      }

      k.commonComponent.coroutines().appScope.launch {
        discoveryController.actionsFlow.collect {
          val action = it
          when (action) {
            is ActionUi.OpenFilePicker -> {
              openFileChooser {
                discoveryController.onSendData(action.deviceUi, OnDataToSend.FilesList(it))
              }
            }
          }
        }
      }


    }
  }

}
