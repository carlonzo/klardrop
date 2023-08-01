import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.carlom.klardrop.FilePickerFactory
import com.carlom.klardrop.KlardropApp
import com.carlom.klardrop.UiDependencies
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.theme.AppTheme

fun main() {
  val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies())
  k.init()

  application {

    Window(
      title = "Klardrop",
      onCloseRequest = ::exitApplication,
      resizable = true,
    ) {
//      val state = rememberWindowState(width = 800.dp, height = 600.dp)

      val uiDependencies = remember(window) {
        object: UiDependencies{
          override fun filePickerFactory(): FilePickerFactory {
            return FilePickerFactory(window)
          }

        }
      }

      AppTheme {

        KlardropApp(k, uiDependencies)
      }

//      k.commonComponent.coroutines().appScope.launch {
//        discoveryController.actionsFlow.collect {
//          val action = it
//          when (action) {
//            is ActionUi.OpenFilePicker -> {
//              openFileChooser {
//                discoveryController.onSendData(action.deviceUi, OnDataToSend.FilesList(it))
//              }
//            }
//          }
//        }
//      }


    }
  }

}
