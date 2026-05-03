package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener


actual class ServiceDiscoveryMdns {

  private val jmdns by lazy {
    val addresses = getAddresses()
    log("ServiceDiscoveryMdns", "jmDNS binding to addresses: ${addresses.map { it.hostAddress }}")
    // Note: on macOS, jmDNS publishing works (other devices see our records via
    // OS-level Bonjour) but inbound browse callbacks may never fire because
    // mDNSResponder owns port 5353 and/or the macOS Application Firewall blocks
    // inbound multicast for unsigned JVM processes. If discovery isn't working
    // there, check System Settings → Network → Firewall and either disable it or
    // explicitly allow incoming connections for the JVM/Klardrop binary.
    addresses.map { address ->
      JmDNS.create(address, address.hostAddress)
    }
  }

  private fun getAddresses(): List<Inet4Address> {
    val addresses = mutableListOf<Inet4Address>()

    NetworkInterface.getNetworkInterfaces().iterator().forEach { networkInterface ->

      if (networkInterface.isLoopback) {
        return@forEach
      }

      if (!networkInterface.isUp) {
        return@forEach
      }

      networkInterface.inetAddresses.iterator().forEach loop2@{ inetAddress ->

        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
          addresses.add(inetAddress)
        }
      }

    }

    return addresses
  }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {

    val serviceTypeLocal = "${serviceType}local."

    return callbackFlow {

      val listenersHolder = mutableListOf<ServiceListener>()

      val listener = createServiceListener(this)
      jmdns.forEach { instance -> instance.addServiceListener(serviceTypeLocal, listener) }

      listenersHolder.add(listener)

      awaitClose {
        listenersHolder.forEach { listener ->
          jmdns.forEach { instance -> instance.removeServiceListener(serviceTypeLocal, listener) }
        }
      }
    }
      .flowOn(Dispatchers.IO)
  }

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {

    suspendCancellableCoroutine<Unit> {

      val registrations = jmdns.map { instance ->

        val jmdnsServiceInfo = javax.jmdns.ServiceInfo.create(
          registerServiceInfo.serviceType,
          registerServiceInfo.serviceName,
          registerServiceInfo.port,
          0,
          0,
          registerServiceInfo.attributes
        )

        instance.registerService(jmdnsServiceInfo)

        jmdnsServiceInfo
      }

      log("ServiceDiscoveryMdns", "publishing service: $registerServiceInfo")

      it.invokeOnCancellation {
        registrations.forEach { jmdnsServiceInfo ->
          jmdns.forEach { instance -> instance.unregisterService(jmdnsServiceInfo) }
        }
      }
    }

  }

  private fun ServiceEvent.toServiceInfo(): ServiceInfo {
    val attributes = txtByteToMap(this.info.textBytes)

    return ServiceInfo(
      port = this.info.port,
      serviceName = this.info.name,
      serviceType = this.info.type,
      attributes = attributes,
      addresses = this.info.inet4Addresses.map { it.hostAddress }
    )
  }

  private fun createServiceListener(producerScope: ProducerScope<ServiceDiscoveryEvent>): ServiceListener {
    return object : ServiceListener {
      override fun serviceAdded(event: ServiceEvent) {
        log("ServiceDiscoveryMdns", "serviceAdded: type=${event.type} name=${event.name}")
      }

      override fun serviceRemoved(event: ServiceEvent) {
        log("ServiceDiscoveryMdns", "serviceRemoved: ${event.info}")
        producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(event.toServiceInfo()))
      }

      override fun serviceResolved(event: ServiceEvent) {
        log("ServiceDiscoveryMdns", "serviceResolved: name=${event.name} addrs=${event.info.inet4Addresses.map { it.hostAddress }} txt=${event.info.propertyNames.toList()}")
        if (event.info.inet4Addresses.isEmpty()) {
          return
        }
        producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(event.toServiceInfo()))
      }

    }

  }

}