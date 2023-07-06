package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
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
import javax.jmdns.ServiceTypeListener
import kotlin.reflect.jvm.internal.impl.descriptors.Visibilities.Private


actual class ServiceDiscoveryMdns() {

  private val jmdns by lazy {
    val addresses = getAddresses()

    addresses.map { address ->
      JmDNS.create(address, address.hostName)
    }
  }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {

    val serviceTypeLocal = "${serviceType}local."

    return callbackFlow {

      val scope: CoroutineScope = this

      val listenersHolder = mutableListOf<ServiceListener>()

      jmdns.forEach { instances ->
        val listener = createServiceListener(this)
        instances.addServiceListener(serviceTypeLocal, listener)

        listenersHolder.add(listener)
      }

      jmdns.forEach {

        it.addServiceTypeListener(object : ServiceTypeListener {
          override fun serviceTypeAdded(event: ServiceEvent) {
            println("Service type added: ${event}")
          }

          override fun subTypeForServiceTypeAdded(event: ServiceEvent) {
            println("Sub type added: ${event}")
          }
        })

      }

      awaitClose {
        jmdns.forEach { instances ->
          listenersHolder.forEach { listener ->
            instances.removeServiceListener(serviceTypeLocal, listener)
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

  private fun createServiceListener( producerScope: ProducerScope<ServiceDiscoveryEvent>): ServiceListener{
    return object : ServiceListener {
      override fun serviceAdded(event: ServiceEvent) {
      }

      override fun serviceRemoved(event: ServiceEvent) {
        log("ServiceDiscoveryMdns", "serviceRemoved: ${event.info}")

        producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(event.toServiceInfo()))
      }

      override fun serviceResolved(event: ServiceEvent) {

        if (event.info.inet4Addresses.isEmpty()){
          return
        } else {
          log("ServiceDiscoveryMdns", "serviceResolved: ${event.name} ${event.info.inet4Addresses.map { it.hostAddress }}")
        }

        producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(event.toServiceInfo()))
      }

    }

  }

}