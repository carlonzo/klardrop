package com.carlom.klardrop.common.mdns

import kotlinx.coroutines.flow.Flow

expect class ServiceDiscoveryMdns {

  fun discoverServices(serviceType: String): Flow<List<ServiceInfo>>
  suspend fun registerService(serviceInfo: ServiceInfo)

}

class ServiceInfo(
  val port: Int,
  val serviceName: String,
  val serviceType: String,
  val attributes: Map<String, String>,
  val address: String? = null
){
  override fun toString(): String {
    return "ServiceInfo(port=$port, serviceName='$serviceName', serviceType='$serviceType', attributes=$attributes)"
  }
}