package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

  actual fun discoverServices(serviceType: String): Flow<List<ServiceInfo>> {
    var listServices = listOf<ServiceInfo>()

    val services = listOf(serviceType, "${serviceType}local.")

    return callbackFlow {

      val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
          log("ServiceDiscoveryMdns", "serviceAdded: $event")

        }

        override fun serviceRemoved(event: ServiceEvent) {
          log("ServiceDiscoveryMdns", "serviceRemoved: $event")

          val newList = listServices.filter { it.serviceName != event.info.name }
          listServices = newList.toMutableList()

          trySend(newList)
        }

        override fun serviceResolved(event: ServiceEvent) {
          log("ServiceDiscoveryMdns", "serviceResolved: ${event.name} ${event.info.inet4Addresses.map { it.hostAddress }}")
          val attributes = txtByteToMap(event.info.textBytes)

          val serviceInfo = ServiceInfo(
            port = event.info.port,
            serviceName = event.info.nameWithoutNumber(),
            serviceType = event.info.type,
            attributes = attributes,
            addresses = event.info.inet4Addresses.map { it.hostAddress }
          )

          val newList = listServices.toMutableList()
          newList.add(serviceInfo)
          listServices = newList

          trySend(newList)
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

  actual suspend fun registerService(serviceInfo: ServiceInfo) {

    suspendCancellableCoroutine<Unit> {

      val registrations = mutableListOf<Pair<JmDNS, javax.jmdns.ServiceInfo>>()

      jmdns.forEach { instance ->
        val jmdnsServiceInfo = javax.jmdns.ServiceInfo.create(
          serviceInfo.serviceType,
          serviceInfo.serviceName,
          serviceInfo.port,
          0,
          0,
          serviceInfo.attributes
        )

        instance.registerService(jmdnsServiceInfo)

        registrations.add(instance to jmdnsServiceInfo)
      }

      log("ServiceDiscoveryMdns", "publishing service: $serviceInfo")

      it.invokeOnCancellation {
        registrations.forEach { (jmdns, jmdnsServiceInfo) ->
          jmdns.unregisterService(jmdnsServiceInfo)
        }
      }
    }


  }

  private fun txtByteToMap(array: ByteArray): Map<String, String> {
    val list = mutableListOf<ByteArray>()

    fun getTxt(array: ByteArray, firstIndex: Int): ByteArray {
      val l = array[firstIndex].toInt()
      return array.copyOfRange(firstIndex + 1, firstIndex + l + 1)
    }

    var index = 0
    while (index < array.size) {
      val txt = getTxt(array, index)
      list.add(txt)
      index += txt.size + 1
    }


    return list.associate {

      val split = it.indexOf('='.code.toByte())


      val key = it.copyOfRange(0, split)
      val value = it.copyOfRange(split + 1, it.size)

      key.decodeToString() to value.decodeToString()
    }
  }

  /**
   * Jmdns append a number at the end of the name if there are 2 services with the same name.
   * This method remove the number at the end of the name
   *
   * From "Izc3Nzf8n14AAA (2)" to "Izc3Nzf8n14AAA"
   */
  private fun javax.jmdns.ServiceInfo.nameWithoutNumber(): String{
    val name = this.name

    return if (name.endsWith(")")) {
      val index = name.lastIndexOf("(")
      name.substring(0, index).trimEnd()
    }else{
      name
    }
  }

}