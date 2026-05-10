package com.carlom.klardrop.common.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

actual class ServiceDiscoveryMdns(private val context: Context) {

  private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

  /**
   * Per-service-type publish locks. Mirrors the iOS fix: re-publishing the same service
   * type before the previous unregister has fully completed makes NsdManager rename to
   * `name (2)`, `name (3)`, … on the wire. Different service types (Klardrop vs Nearby)
   * use independent mutexes so they don't block each other while each holds its own
   * publish slot for the lifetime of the registration.
   */
  private val publishMutexByServiceType = mutableMapOf<String, Mutex>()
  private val mutexMapMutex = Mutex()

  private suspend fun publishMutexFor(serviceType: String): Mutex =
    mutexMapMutex.withLock { publishMutexByServiceType.getOrPut(serviceType) { Mutex() } }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {

    return callbackFlow {

      val producer = this

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

          val resolveListener = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
              log("ServiceDiscoveryMdns", "onServiceInfoCallbackRegistrationFailed: $errorCode")
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
              log("ServiceDiscoveryMdns", "onServiceUpdated: $serviceInfo")
              producer.launch {
                send(ServiceDiscoveryEvent.ServiceFound(serviceInfo.toServiceInfo()))
              }
            }

            override fun onServiceLost() {
              log("ServiceDiscoveryMdns", "onServiceLost (callback)")
            }

            override fun onServiceInfoCallbackUnregistered() {
              log("ServiceDiscoveryMdns", "onServiceInfoCallbackUnregistered")
            }
          }

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nsdManager.registerServiceInfoCallback(serviceInfo, { it.run() }, resolveListener)
          } else {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
              override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log("ServiceDiscoveryMdns", "onResolveFailed: $serviceInfo $errorCode")
              }

              override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                log("ServiceDiscoveryMdns", "onServiceResolved: $serviceInfo")
                producer.launch {
                  send(ServiceDiscoveryEvent.ServiceFound(serviceInfo.toServiceInfo()))
                }
              }
            })
          }
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
    // Hold the per-service-type publish mutex for the lifetime of this registration.
    // DiscoveryNetwork.republishKlardrop / republishNearbyShare cancel the previous
    // job before launching the next; cancellation propagates here, the finally block
    // requests unregister and waits for onServiceUnregistered before releasing the
    // mutex. The next re-publish for the same type then can't collide with our
    // teardown — without this, NsdManager renames the new instance to `name (2)`
    // and the old one keeps living until its own unregister eventually completes.
    publishMutexFor(registerServiceInfo.serviceType).withLock {
      val unregistered = CompletableDeferred<Unit>()

      val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceRegistered: $serviceInfo")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onRegistrationFailed: $serviceInfo $errorCode")
          // Treat failure as immediate "stopped" so the next caller doesn't wait
          // the full timeout for an unregister that will never come.
          unregistered.complete(Unit)
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
          log("ServiceDiscoveryMdns", "onServiceUnregistered: $serviceInfo")
          unregistered.complete(Unit)
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
          log("ServiceDiscoveryMdns", "onUnregistrationFailed: $serviceInfo $errorCode")
          // Even on failure, release the next caller — it's better to risk a stale
          // entry on the wire than to deadlock the publish chain forever.
          unregistered.complete(Unit)
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

      log("ServiceDiscoveryMdns", "Registering: $registerServiceInfo")

      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) {
          log("ServiceDiscoveryMdns", "unregister requested for ${registerServiceInfo.serviceName}")
          runCatching { nsdManager.unregisterService(listener) }
            .onFailure { log("ServiceDiscoveryMdns", "unregisterService threw: ${it.message}") }
          val ack = withTimeoutOrNull(2.seconds) { unregistered.await() }
          log(
            "ServiceDiscoveryMdns",
            if (ack != null) "unregister confirmed for ${registerServiceInfo.serviceName}"
            else "unregister TIMED OUT for ${registerServiceInfo.serviceName} — releasing publish lock anyway",
          )
          runCatching { lock.release() }
        }
      }
    }
  }

  /**
   * Android delegates discovery lifecycle to [NsdManager], which already reacts
   * to network transitions via the system. The DiscoveryNetwork still cancels
   * and re-launches its discovery jobs on a [com.carlom.klardrop.common.network.NetworkChangeEvent]
   * — that re-creates fresh [NsdManager.discoverServices] subscriptions and is
   * sufficient. Nothing to tear down here.
   */
  actual suspend fun restart() {
    log("ServiceDiscoveryMdns", "restart: no-op on Android (NsdManager handles network transitions)")
  }

  private fun acquireWifiLock(): WifiManager.MulticastLock {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("klardrop-multicast-lock")
    lock.setReferenceCounted(true)
    lock.acquire()

    return lock
  }

  private fun NsdServiceInfo.toServiceInfo(): ServiceInfo {
    val attributes = this.attributes.mapValues { it.value.decodeToString() }

    val addresses: List<String> =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7) {
        this.hostAddresses.mapNotNull { it.hostAddress }
      } else {
        @Suppress("DEPRECATION")
        this.host?.hostAddress?.let { listOf(it) } ?: emptyList()
      }

    return ServiceInfo(
      this.port,
      this.serviceName,
      this.serviceType,
      attributes,
      addresses
    )
  }

}