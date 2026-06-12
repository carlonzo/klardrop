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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds
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

  /**
   * Mutex serializing pre-Tiramisu [NsdManager.resolveService] calls.
   *
   * NsdManager allows only ONE resolveService in flight at a time on API < 34.
   * A browse restart re-fires [onServiceFound] for ALL previously-found services
   * simultaneously, so without serialization the second call gets
   * FAILURE_ALREADY_ACTIVE (3) and the peer is never resolved.
   *
   * API 34+ uses [registerServiceInfoCallback] which supports concurrent resolve
   * and does not need this mutex.
   */
  private val resolveMutex = Mutex()

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> {

    return callbackFlow {

      val producer = this

      // Track active API-34+ ServiceInfoCallback instances so we can unregister
      // them all in awaitClose / on ServiceLost to avoid callback leaks across
      // browse restarts (B23 hardening: repeated restarts would otherwise
      // accumulate callbacks, each of which keeps its own NsdManager slot alive).
      val activeCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()

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

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: register a ServiceInfoCallback for live updates.
            // Track it so we can unregister on ServiceLost or flow close.
            val key = serviceInfo.serviceName
            val resolveListener = object : NsdManager.ServiceInfoCallback {
              override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                log("ServiceDiscoveryMdns", "onServiceInfoCallbackRegistrationFailed: $errorCode for $key")
                activeCallbacks.remove(key)
              }

              override fun onServiceUpdated(info: NsdServiceInfo) {
                log("ServiceDiscoveryMdns", "onServiceUpdated: $info")
                producer.launch {
                  send(ServiceDiscoveryEvent.ServiceFound(info.toServiceInfo()))
                }
              }

              override fun onServiceLost() {
                log("ServiceDiscoveryMdns", "onServiceLost (callback) for $key")
                // Unregister eagerly on ServiceLost to release the NsdManager slot.
                val cb = activeCallbacks.remove(key)
                if (cb != null) {
                  runCatching { nsdManager.unregisterServiceInfoCallback(cb) }
                    .onFailure { log("ServiceDiscoveryMdns", "unregisterServiceInfoCallback failed for $key: ${it.message}") }
                }
              }

              override fun onServiceInfoCallbackUnregistered() {
                log("ServiceDiscoveryMdns", "onServiceInfoCallbackUnregistered for $key")
                activeCallbacks.remove(key)
              }
            }
            // Remove any stale callback for this key before registering (idempotent on browse restart).
            activeCallbacks.put(key, resolveListener)?.let { stale ->
              runCatching { nsdManager.unregisterServiceInfoCallback(stale) }
                .onFailure { log("ServiceDiscoveryMdns", "unregister stale callback for $key: ${it.message}") }
            }
            nsdManager.registerServiceInfoCallback(serviceInfo, { it.run() }, resolveListener)
          } else {
            // API < 34: resolveService is limited to ONE concurrent call.
            // Serialize all resolve attempts through resolveMutex so that a browse
            // restart firing onServiceFound for many services at once doesn't hit
            // FAILURE_ALREADY_ACTIVE (errorCode 3).
            producer.launch {
              resolveMutex.withLock {
                val deferred = CompletableDeferred<Unit>()
                @Suppress("DEPRECATION")
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                  override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    log("ServiceDiscoveryMdns", "onResolveFailed: $serviceInfo errorCode=$errorCode")
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                      // Serialization should prevent this, but as a safety net, retry
                      // after a short back-off rather than silently dropping the peer.
                      log("ServiceDiscoveryMdns", "FAILURE_ALREADY_ACTIVE for ${serviceInfo.serviceName}; will retry via back-off")
                      producer.launch {
                        delay(RESOLVE_RETRY_BACKOFF)
                        resolveMutex.withLock {
                          val retryDeferred = CompletableDeferred<Unit>()
                          @Suppress("DEPRECATION")
                          nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(s: NsdServiceInfo, c: Int) {
                              log("ServiceDiscoveryMdns", "onResolveFailed (retry): $s errorCode=$c")
                              retryDeferred.complete(Unit)
                            }
                            override fun onServiceResolved(s: NsdServiceInfo) {
                              log("ServiceDiscoveryMdns", "onServiceResolved (retry): $s")
                              producer.launch { send(ServiceDiscoveryEvent.ServiceFound(s.toServiceInfo())) }
                              retryDeferred.complete(Unit)
                            }
                          })
                          retryDeferred.await()
                        }
                      }
                    }
                    deferred.complete(Unit)
                  }

                  override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    log("ServiceDiscoveryMdns", "onServiceResolved: $serviceInfo")
                    producer.launch {
                      send(ServiceDiscoveryEvent.ServiceFound(serviceInfo.toServiceInfo()))
                    }
                    deferred.complete(Unit)
                  }
                })
                deferred.await()
              }
            }
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
        // Unregister all tracked ServiceInfoCallbacks to prevent leaks across browse restarts.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          activeCallbacks.values.forEach { cb ->
            runCatching { nsdManager.unregisterServiceInfoCallback(cb) }
              .onFailure { log("ServiceDiscoveryMdns", "unregisterServiceInfoCallback in awaitClose: ${it.message}") }
          }
          activeCallbacks.clear()
        }
        lock.release()
        nsdManager.stopServiceDiscovery(listener)
      }

    }

  }

  companion object {
    /**
     * Back-off delay before retrying a resolve that hit FAILURE_ALREADY_ACTIVE on
     * API < 34. One slot is freed when the first in-flight resolve completes and
     * releases the mutex; this small delay gives the system time to drain.
     */
    val RESOLVE_RETRY_BACKOFF = 200.milliseconds
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