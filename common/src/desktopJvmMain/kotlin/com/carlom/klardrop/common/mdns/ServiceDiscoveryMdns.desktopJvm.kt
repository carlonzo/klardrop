package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.flow.Flow

internal interface ServiceDiscoveryMdnsBackend {
  fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent>
  suspend fun registerService(registerServiceInfo: RegisterServiceInfo)
}

actual class ServiceDiscoveryMdns {

  private val backend: ServiceDiscoveryMdnsBackend by lazy {
    val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    val isMac = osName.contains("mac") || osName.contains("darwin")
    if (isMac) {
      log("ServiceDiscoveryMdns", "using native Bonjour backend (libdns_sd)")
      BonjourServiceDiscoveryMdns()
    } else {
      log("ServiceDiscoveryMdns", "using jmDNS backend (os=$osName)")
      JmDnsServiceDiscoveryMdns()
    }
  }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> =
    backend.discoverServices(serviceType)

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) =
    backend.registerService(registerServiceInfo)
}
