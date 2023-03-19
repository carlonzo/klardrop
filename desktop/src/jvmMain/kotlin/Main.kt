import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop


fun main() {

  val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies())
  k.init()

  val discoveryController = DiscoveryUIController(k.commonComponent)

  application {


    Window(onCloseRequest = ::exitApplication) {


      DiscoveryDashboard(discoveryController)


    }
  }

}
