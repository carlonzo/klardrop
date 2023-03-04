package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.envelopes.StaticEnvelope
import com.carlom.klardrop.common.communication.envelopes.StreamEnvelope
import com.carlom.klardrop.common.persistence.DeviceInfo
import kotlinx.coroutines.flow.Flow

interface Postman {

  suspend fun sendEnvelope(device: DeviceInfo, envelope: StaticEnvelope)
  fun streamEnvelope(device: DeviceInfo, envelope: StreamEnvelope): Flow<Int>

}


internal class PostmanImpl(
  private val devices: Flow<Set<DeviceInfo>>
) : Postman {

  fun start() {

  }

  override suspend fun sendEnvelope(device: DeviceInfo, envelope: StaticEnvelope) {
    TODO("Not yet implemented")
  }

  override fun streamEnvelope(device: DeviceInfo, envelope: StreamEnvelope): Flow<Int> {
    TODO("Not yet implemented")
  }


}