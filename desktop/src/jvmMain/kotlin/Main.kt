import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.carlom.klardrop.common.App
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.SocketBroadcastUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


fun main() {

  val k = Klardrop(internalPlatformDependency = InternalPlatformDependencies())
  k.init()
  val d = k.discovery()

  val discoveryFlow = d.start()



  application {


    Window(onCloseRequest = ::exitApplication) {


      val received by discoveryFlow.collectAsState("nothing")

      Text(
        text = received
      )

    }
  }

}
