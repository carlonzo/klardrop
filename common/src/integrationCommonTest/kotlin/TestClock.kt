import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.test.TestDispatcher

class TestClock(private val testDispatcher: TestDispatcher) : Clock() {
  override fun currentTimeMillis(): Long {
    return testDispatcher.scheduler.currentTime
  }
}