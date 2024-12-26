import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.coroutines.CoroutineContext

class TestCoroutines(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : Coroutines {

  private val handler = CoroutineExceptionHandler { _, exception ->
    log("TestCoroutines", "CoroutineExceptionHandler got ${exception.message}", exception)
    throw exception
  }

  private val scope = TestScope(dispatcher)

  override fun newScope(): CoroutineScope {
    return CoroutineScope(dispatcher + handler)
  }

  override fun newScope(context: CoroutineContext): CoroutineScope {
    return CoroutineScope(handler + context + dispatcher)
  }

  override val appScope: CoroutineScope
    get() = scope

  override val ioDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO
  override val mainDispatcher: CoroutineDispatcher
    get() = dispatcher
  override val cpuDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
}