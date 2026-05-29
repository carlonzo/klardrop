package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

internal class JmDnsServiceDiscoveryMdns : ServiceDiscoveryMdnsBackend {

  // jmDNS instances are torn down + rebuilt on demand so [restart] can recover from
  // a stale set of NIC bindings (post-sleep/wake or NIC change). The mutex serializes
  // restart against in-flight discovery / register calls.
  private val mutex = Mutex()
  private var jmdnsInstances: List<JmDNS> = emptyList()

  private suspend fun acquireJmdns(): List<JmDNS> = mutex.withLock {
    if (jmdnsInstances.isEmpty()) {
      val addresses = getAddresses()
      log("JmDnsMdns", "jmDNS binding to addresses: ${addresses.map { it.hostAddress }}")
      jmdnsInstances = addresses.map { address -> JmDNS.create(address, address.hostAddress) }
    }
    jmdnsInstances
  }

  private fun getAddresses(): List<Inet4Address> {
    val addresses = mutableListOf<Inet4Address>()
    NetworkInterface.getNetworkInterfaces().iterator().forEach { networkInterface ->
      if (networkInterface.isLoopback) return@forEach
      if (!networkInterface.isUp) return@forEach
      networkInterface.inetAddresses.iterator().forEach { inetAddress ->
        if (inetAddress.isLoopbackAddress) return@forEach
        if (inetAddress !is Inet4Address) return@forEach
        // Skip Tailscale / CGNAT (100.64.0.0/10). That address is only reachable over the tailnet,
        // never on the LAN — binding jmDNS to it and advertising it makes peers waste connect
        // attempts on an unreachable address and adds an asymmetric-routing path on multi-homed
        // hosts. LAN sharing should only ever announce real LAN interfaces.
        if (isCgnat(inetAddress)) {
          log("JmDnsMdns", "Skipping CGNAT/Tailscale address ${inetAddress.hostAddress} for mDNS")
          return@forEach
        }
        addresses.add(inetAddress)
      }
    }
    return addresses
  }

  /** True for the 100.64.0.0/10 carrier-grade-NAT range that Tailscale (and CGNAT) use. */
  private fun isCgnat(address: Inet4Address): Boolean {
    val bytes = address.address
    val first = bytes[0].toInt() and 0xFF
    val second = bytes[1].toInt() and 0xFF
    return first == 100 && second in 64..127
  }

  override fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {
    val serviceTypeLocal = "${serviceType}local."

    return callbackFlow {
      val jmdns = acquireJmdns()
      val listener = createServiceListener(this)
      jmdns.forEach { instance -> instance.addServiceListener(serviceTypeLocal, listener) }

      awaitClose {
        jmdns.forEach { instance ->
          runCatching { instance.removeServiceListener(serviceTypeLocal, listener) }
        }
      }
    }.flowOn(Dispatchers.IO)
  }

  override suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {
    val jmdns = acquireJmdns()
    suspendCancellableCoroutine<Unit> {
      val registrations = jmdns.map { instance ->
        val jmdnsServiceInfo = javax.jmdns.ServiceInfo.create(
          registerServiceInfo.serviceType,
          registerServiceInfo.serviceName,
          registerServiceInfo.port,
          0,
          0,
          registerServiceInfo.attributes,
        )
        instance.registerService(jmdnsServiceInfo)
        jmdnsServiceInfo
      }

      log("JmDnsMdns", "publishing service: $registerServiceInfo")

      it.invokeOnCancellation {
        registrations.forEach { jmdnsServiceInfo ->
          jmdns.forEach { instance ->
            runCatching { instance.unregisterService(jmdnsServiceInfo) }
          }
        }
      }
    }
  }

  override suspend fun restart() {
    mutex.withLock {
      log("JmDnsMdns", "restart: closing ${jmdnsInstances.size} jmDNS instance(s)")
      jmdnsInstances.forEach { instance ->
        runCatching { instance.unregisterAllServices() }
        runCatching { instance.close() }
      }
      jmdnsInstances = emptyList()
    }
  }

  private fun ServiceEvent.toServiceInfo(): ServiceInfo {
    val attributes = txtByteToMap(this.info.textBytes)
    return ServiceInfo(
      port = this.info.port,
      serviceName = this.info.name,
      serviceType = this.info.type,
      attributes = attributes,
      addresses = this.info.inet4Addresses.map { it.hostAddress },
    )
  }

  private fun createServiceListener(producerScope: ProducerScope<ServiceDiscoveryEvent>): ServiceListener {
    return object : ServiceListener {
      override fun serviceAdded(event: ServiceEvent) {
        log("JmDnsMdns", "serviceAdded: type=${event.type} name=${event.name}")
      }

      override fun serviceRemoved(event: ServiceEvent) {
        log("JmDnsMdns", "serviceRemoved: ${event.info}")
        producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(event.toServiceInfo()))
      }

      override fun serviceResolved(event: ServiceEvent) {
        log("JmDnsMdns", "serviceResolved: name=${event.name} addrs=${event.info.inet4Addresses.map { it.hostAddress }} txt=${event.info.propertyNames.toList()}")
        if (event.info.inet4Addresses.isEmpty()) return
        producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(event.toServiceInfo()))
      }
    }
  }
}
