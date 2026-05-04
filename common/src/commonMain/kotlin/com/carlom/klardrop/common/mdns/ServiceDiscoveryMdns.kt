package com.carlom.klardrop.common.mdns

import kotlinx.coroutines.flow.Flow

expect class ServiceDiscoveryMdns {
  fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent>
  suspend fun registerService(registerServiceInfo: RegisterServiceInfo)

  /**
   * Tear down internal mDNS resources so they can be rebuilt against the
   * current network state. After [restart], in-flight discovery flows and
   * service registrations are dead and must be re-issued by the caller (this
   * is what [com.carlom.klardrop.common.discovery.DiscoveryNetwork] does on
   * a [com.carlom.klardrop.common.network.NetworkChangeEvent]).
   */
  suspend fun restart()
}

sealed interface ServiceDiscoveryEvent {
  val serviceInfo: ServiceInfo

  data class ServiceFound(override val serviceInfo: ServiceInfo) : ServiceDiscoveryEvent
  data class ServiceLost(override val serviceInfo: ServiceInfo) : ServiceDiscoveryEvent
}

data class ServiceInfo(
  val port: Int,
  val serviceName: String,
  val serviceType: String,
  val attributes: Map<String, String>,
  val addresses: List<String>
)

data class RegisterServiceInfo(
  val port: Int,
  val serviceName: String,
  val serviceType: String,
  val attributes: Map<String, String>
)