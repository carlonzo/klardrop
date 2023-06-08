package com.carlom.klardrop.common.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

actual class ServiceDiscoveryMdns(private val context: Context) {

  private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

  actual fun discoverServices(serviceType: String): Flow<List<ServiceInfo>> {
    var listServices = mutableListOf<ServiceInfo>()

    return callbackFlow {

      val listener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onStartDiscoveryFailed: $serviceType $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onStopDiscoveryFailed: $serviceType $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {
          log("ServiceDiscoveryMdns", "onDiscoveryStarted: $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String) {
          log("ServiceDiscoveryMdns", "onDiscoveryStopped: $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceFound: $serviceInfo")
          val attributes = serviceInfo.attributes.mapValues { it.value.decodeToString() }

          val newList = listServices.toMutableList()
          newList.add(
            ServiceInfo(serviceInfo.port, serviceInfo.serviceName, serviceInfo.serviceType, attributes ,serviceInfo.host.hostAddress)
          )
          listServices = newList

          trySend(newList)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceLost: $serviceInfo")
          listServices.removeAll { it.serviceName == serviceInfo.serviceName }
          trySend(listServices)
        }

      }

      val lock = acquireWifiLock()

      nsdManager.discoverServices(
        serviceType,
        NsdManager.PROTOCOL_DNS_SD,
        listener
      )

      awaitClose {
        lock.release()
        nsdManager.stopServiceDiscovery(listener)
      }

    }

  }

  actual suspend fun registerService(serviceInfo: ServiceInfo) {

    suspendCancellableCoroutine<Unit> {

      val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceRegistered: $serviceInfo")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onRegistrationFailed: $serviceInfo $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceUnregistered: $serviceInfo")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onUnregistrationFailed: $serviceInfo $errorCode")
        }

      }

      val lock = acquireWifiLock()

      nsdManager.registerService(
        NsdServiceInfo().apply {
          serviceName = serviceInfo.serviceName
          serviceType = serviceInfo.serviceType
          port = serviceInfo.port

          serviceInfo.attributes.forEach { (key, value) ->
            setAttribute(key, value)
          }
        },
        NsdManager.PROTOCOL_DNS_SD,
        listener
      )

      it.invokeOnCancellation {
        lock.release()
        nsdManager.unregisterService(listener)
      }

    }

  }

  private fun acquireWifiLock(): WifiManager.MulticastLock {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("multicastLock");
    lock.setReferenceCounted(true);
    lock.acquire()

    return lock
  }


}