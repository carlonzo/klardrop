import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.nextString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.random.Random

class FakeLocalPropertiesRepository(private val currentDeviceId: String = Random.nextString(5)): LocalPropertiesRepository {
  override val properties: MutableStateFlow<KlardropProperties> = MutableStateFlow(KlardropProperties(currentDeviceId))

  override suspend fun getProperty(): KlardropProperties {
    return properties.first()
  }

  override suspend fun save(properties: KlardropProperties) {
    this.properties.emit(properties)
  }

  override suspend fun saveCustomDeviceName(customDeviceName: String?) {
    val current = getProperty()
    save(current.copy(customDeviceName = customDeviceName))
  }

  override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {
    save(getProperty().copy(backgroundDiscoveryEnabled = enabled))
  }
}