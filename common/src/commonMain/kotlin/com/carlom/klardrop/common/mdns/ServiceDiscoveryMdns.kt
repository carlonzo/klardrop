package com.carlom.klardrop.common.mdns

import kotlinx.coroutines.flow.Flow

internal expect class ServiceDiscoveryMdns {
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

internal sealed interface ServiceDiscoveryEvent {
  val serviceInfo: ServiceInfo

  data class ServiceFound(override val serviceInfo: ServiceInfo) : ServiceDiscoveryEvent
  data class ServiceLost(override val serviceInfo: ServiceInfo) : ServiceDiscoveryEvent
}

internal data class ServiceInfo(
  internal val port: Int,
  internal val serviceName: String,
  internal val serviceType: String,
  internal val attributes: Map<String, String>,
  internal val addresses: List<String>
)

internal data class RegisterServiceInfo(
  internal val port: Int,
  internal val serviceName: String,
  internal val serviceType: String,
  internal val attributes: Map<String, String>
)