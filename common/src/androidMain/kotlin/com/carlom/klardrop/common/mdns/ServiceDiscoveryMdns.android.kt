package com.carlom.klardrop.common.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual class ServiceDiscoveryMdns(private val context: Context) {

  private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {

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

          nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
              log("ServiceDiscoveryMdns", "onResolveFailed: $serviceInfo $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
              log("ServiceDiscoveryMdns", "onServiceResolved: $serviceInfo")
              trySend(ServiceDiscoveryEvent.ServiceFound(serviceInfo.toServiceInfo()))
            }

          })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceLost: $serviceInfo")

          val service = serviceInfo.toServiceInfo()

          trySend(ServiceDiscoveryEvent.ServiceLost(service))
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

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {

    suspendCancellableCoroutine {

      val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceRegistered: $serviceInfo")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onRegistrationFailed: $serviceInfo $errorCode")
          it.resumeWithException(Exception("Registration failed with error code $errorCode"))
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceUnregistered: $serviceInfo")
          it.resume(Unit)
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onUnregistrationFailed: $serviceInfo $errorCode")
        }

      }

      val lock = acquireWifiLock()

      nsdManager.registerService(
        NsdServiceInfo().apply {
          serviceName = registerServiceInfo.serviceName
          serviceType = registerServiceInfo.serviceType
          port = registerServiceInfo.port

          registerServiceInfo.attributes.forEach { (key, value) ->
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

  private fun NsdServiceInfo.toServiceInfo(): ServiceInfo {
    val attributes = this.attributes.mapValues { it.value.decodeToString() }

    val addresses = this.host.hostAddress

    return ServiceInfo(
      this.port,
      this.serviceName,
      this.serviceType,
      attributes,
      addresses?.let { listOf(it) } ?: emptyList()
    )
  }

}