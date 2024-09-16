import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.coroutines.CoroutineContext

class TestCoroutines(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : Coroutines {

  private val scope = TestScope(dispatcher)

  override fun newScope(): CoroutineScope {
    return CoroutineScope(dispatcher)
  }

  override fun newScope(context: CoroutineContext): CoroutineScope {
    return CoroutineScope(context + dispatcher)
  }

  override val appScope: CoroutineScope
    get() = scope
  override val ioDispatcher: CoroutineDispatcher
    get() = dispatcher
  override val mainDispatcher: CoroutineDispatcher
    get() = dispatcher
  override val cpuDispatcher: CoroutineDispatcher
    get() = dispatcher
}