package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import javax.jmdns.impl.JmmDNSImpl


actual class ServiceDiscoveryMdns {

  private val jmdns by lazy { JmmDNSImpl() }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {

    val serviceTypeLocal = "${serviceType}local."

    return callbackFlow {

      val listenersHolder = mutableListOf<ServiceListener>()

      val listener = createServiceListener(this)
      jmdns.addServiceListener(serviceTypeLocal, listener)

      listenersHolder.add(listener)

      awaitClose {
        listenersHolder.forEach { listener ->
          jmdns.removeServiceListener(serviceTypeLocal, listener)
        }
      }
    }
      .flowOn(Dispatchers.IO)
  }

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {

    suspendCancellableCoroutine<Unit> {

      val registrations = mutableListOf<javax.jmdns.ServiceInfo>()


      val jmdnsServiceInfo = javax.jmdns.ServiceInfo.create(
        registerServiceInfo.serviceType,
        registerServiceInfo.serviceName,
        registerServiceInfo.port,
        0,
        0,
        registerServiceInfo.attributes
      )

      jmdns.registerService(jmdnsServiceInfo)

      log("ServiceDiscoveryMdns", "publishing service: $registerServiceInfo")

      it.invokeOnCancellation {
        registrations.forEach { jmdnsServiceInfo ->
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

  private fun createServiceListener(producerScope: ProducerScope<ServiceDiscoveryEvent>): ServiceListener {
    return object : ServiceListener {
      override fun serviceAdded(event: ServiceEvent) {
      }

      override fun serviceRemoved(event: ServiceEvent) {
        log("ServiceDiscoveryMdns", "serviceRemoved: ${event.info}")

        producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(event.toServiceInfo()))
      }

      override fun serviceResolved(event: ServiceEvent) {

        if (event.info.inet4Addresses.isEmpty()) {
          return
        } else {
          log("ServiceDiscoveryMdns", "serviceResolved: ${event.name} ${event.info.inet4Addresses.map { it.hostAddress }}")
        }

        producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(event.toServiceInfo()))
      }

    }

  }

}