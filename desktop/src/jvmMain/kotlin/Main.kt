import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.carlom.klardrop.common.App
import com.carlom.klardrop.common.SocketBroadcastUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() {

  androidx.compose.ui.window.application {
    val flow = SocketBroadcastUtility.listenToBroadcast(2121)
    continuousCounter()

    Window(onCloseRequest = ::exitApplication) {
      App(flow)
    }
  }

}


private fun continuousCounter() {
  val channel = SocketBroadcastUtility.sendMessageChannel(2121)

  GlobalScope.launch(Dispatchers.IO) {
    var counter = 0
    while (true) {
      channel.send("Message number: $counter\n")
      counter++
      delay(2000)
    }
  }
}