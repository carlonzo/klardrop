import com.carlom.klardrop.common.persistence.KlardropProperties
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class FakeLocalPropertiesRepository: LocalPropertiesRepository {

  override val properties: MutableStateFlow<KlardropProperties> = MutableStateFlow(KlardropProperties(""))

  override suspend fun getProperty(): KlardropProperties {
    return properties.first()
  }

  override suspend fun save(properties: KlardropProperties) {
    this.properties.emit(properties)
  }
}