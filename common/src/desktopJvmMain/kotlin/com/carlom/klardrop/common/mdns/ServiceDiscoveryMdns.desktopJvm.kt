package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener


actual class ServiceDiscoveryMdns() {

  private val jmdns by lazy {
    val addresses = getAddresses()

    addresses.map { address ->
      JmDNS.create(address, address.hostName)
    }
  }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {

    val services = listOf(serviceType, "${serviceType}local.")

    return callbackFlow {

      val scope: CoroutineScope = this

      val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
          log("ServiceDiscoveryMdns", "serviceAdded: $event")

        }

        override fun serviceRemoved(event: ServiceEvent) {
          log("ServiceDiscoveryMdns", "serviceRemoved: $event")

          scope.launch {
            send(ServiceDiscoveryEvent.ServiceLost(event.toServiceInfo()))
          }
        }

        override fun serviceResolved(event: ServiceEvent) {
          log("ServiceDiscoveryMdns", "serviceResolved: ${event.name} ${event.info.inet4Addresses.map { it.hostAddress }}")

          scope.launch {
            send(ServiceDiscoveryEvent.ServiceFound(event.toServiceInfo()))
          }
        }

      }

      jmdns.forEach { instances ->
        services.forEach { service ->
          instances.addServiceListener(service, listener)
        }
      }

      awaitClose {
        jmdns.forEach { instances ->
          services.forEach {
            instances.removeServiceListener(it, listener)
          }

        }
      }
    }
  }

  private fun getAddresses(): List<InetAddress> {
    val addresses = mutableListOf<InetAddress>()

    NetworkInterface.getNetworkInterfaces().iterator().forEach { networkInterface ->
      networkInterface.inetAddresses.iterator().forEach { inetAddress ->

        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
          addresses.add(inetAddress)
        }
      }

    }

    return addresses
  }

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {

    suspendCancellableCoroutine<Unit> {

      val registrations = mutableListOf<Pair<JmDNS, javax.jmdns.ServiceInfo>>()

      jmdns.forEach { instance ->
        val jmdnsServiceInfo = javax.jmdns.ServiceInfo.create(
          registerServiceInfo.serviceType,
          registerServiceInfo.serviceName,
          registerServiceInfo.port,
          0,
          0,
          registerServiceInfo.attributes
        )

        instance.registerService(jmdnsServiceInfo)

        registrations.add(instance to jmdnsServiceInfo)
      }

      log("ServiceDiscoveryMdns", "publishing service: $registerServiceInfo")

      it.invokeOnCancellation {
        registrations.forEach { (jmdns, jmdnsServiceInfo) ->
          jmdns.unregisterService(jmdnsServiceInfo)
        }
      }
    }


  }


  /**
   * Jmdns append a number at the end of the name if there are 2 services with the same name.
   * This method remove the number at the end of the name
   *
   * From "Izc3Nzf8n14AAA (2)" to "Izc3Nzf8n14AAA"
   */
  private fun javax.jmdns.ServiceInfo.nameWithoutNumber(): String {
    val name = this.name

    return if (name.endsWith(")")) {
      val index = name.lastIndexOf("(")
      name.substring(0, index).trimEnd()
    } else {
      name
    }
  }

  private fun ServiceEvent.toServiceInfo(): ServiceInfo {
    val attributes = txtByteToMap(this.info.textBytes)

    return ServiceInfo(
      port = this.info.port,
      serviceName = this.info.nameWithoutNumber(),
      serviceType = this.info.type,
      attributes = attributes,
      addresses = this.info.inet4Addresses.map { it.hostAddress }
    )
  }

}