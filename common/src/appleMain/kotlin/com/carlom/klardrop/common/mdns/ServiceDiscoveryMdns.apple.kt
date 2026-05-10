package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSMutableData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop.Companion.mainRunLoop
import platform.Foundation.appendData
import platform.Foundation.create
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.seconds

actual class ServiceDiscoveryMdns {

  private val browserReferencesHolder = mutableListOf<NSNetServiceBrowser>()
  private val browserDelegateReferencesHolder = mutableListOf<BonjourBrowserDelegate>()
  private val serviceReferencesHolder = mutableListOf<NSNetService>()

  /**
   * Per-service-type publish locks. registerService blocks inside awaitCancellation()
   * for the lifetime of the published NSNetService, so a single shared mutex would
   * deadlock concurrent publishes for *different* service types — the second one would
   * never get the lock. We only need to serialize re-publishes for the **same** service
   * type (where mDNSResponder collides and renames to `name (2)`, `name (3)`, …); a
   * Klardrop publish and a Nearby Share publish are independent and can run concurrently.
   *
   * Keyed by serviceType (`_klardrop._tcp.` etc) since within a process each service-type
   * has at most one logical publish job (see DiscoveryNetwork.republishKlardrop / Nearby).
   * Guarded by [mutexMapMutex] for safe concurrent insertion.
   */
  private val publishMutexByServiceType = mutableMapOf<String, Mutex>()
  private val mutexMapMutex = Mutex()

  private suspend fun publishMutexFor(serviceType: String): Mutex =
    mutexMapMutex.withLock { publishMutexByServiceType.getOrPut(serviceType) { Mutex() } }


  actual fun discoverServices(serviceType: String) = callbackFlow {

    val browser = NSNetServiceBrowser()
    val serviceDelegate = NetServiceDelegate(this)
    val delegate = BonjourBrowserDelegate(this, serviceDelegate)

    browserReferencesHolder.add(browser)
    browserDelegateReferencesHolder.add(delegate)

    browser.delegate = delegate
    browser.includesPeerToPeer = true

    browser.scheduleInRunLoop(mainRunLoop, NSDefaultRunLoopMode)

    val domain = "local."
    val cleanServiceType = serviceType.removeSuffix(".local.").removeSuffix(".")

    browser.searchForServicesOfType(type = cleanServiceType, inDomain = domain)

    log("ServiceDiscoveryMdns", "Bonjour discovery started for $cleanServiceType in domain $domain")


    awaitClose {
      log("ServiceDiscoveryMdns","closing browser for $serviceType")
      browser.removeFromRunLoop(mainRunLoop, NSDefaultRunLoopMode)
      browser.stop()
      browser.delegate = null
      browserReferencesHolder.removeAll { ref -> ref === browser }
      browserDelegateReferencesHolder.removeAll { ref -> ref === delegate }
    }

  }

  /**
   * On Apple, Bonjour (mDNSResponder) handles network transitions transparently
   * and the per-flow [awaitClose] hooks tear down the browser/service when
   * DiscoveryNetwork cancels its discovery jobs. There is nothing additional to
   * tear down here — restarting amounts to re-launching the discovery flows,
   * which the caller does.
   */
  actual suspend fun restart() {
    log("ServiceDiscoveryMdns", "restart: no-op on Apple (Bonjour handles transitions)")
  }

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {
    // Hold the per-service-type publish mutex for the entire lifetime of this
    // NSNetService. Cancelling the surrounding job (DiscoveryNetwork.republishKlardrop
    // calls cancel() before launching the next one) propagates into the
    // awaitCancellation()/finally pair below, where we synchronously wait for stop()
    // to complete before releasing the mutex. The next re-publish for the *same*
    // service type can then proceed without colliding with our teardown. Different
    // service types (Klardrop vs Nearby) use different mutexes so they don't deadlock.
    publishMutexFor(registerServiceInfo.serviceType).withLock {
      val service = NSNetService(
        domain = "local.",
        type = registerServiceInfo.serviceType,
        name = registerServiceInfo.serviceName,
        port = registerServiceInfo.port
      )
      val delegate = PublishDelegate(registerServiceInfo.serviceName)
      service.delegate = delegate

      serviceReferencesHolder.add(service)

      service.setTXTRecordData(createTXTRecordData(registerServiceInfo.attributes))
      service.includesPeerToPeer = true

      service.publish()
      log("ServiceDiscoveryMdns", "publish() called for ${registerServiceInfo.serviceName}")

      try {
        awaitCancellation()
      } finally {
        // Run cleanup even when we're being cancelled. NonCancellable lets us
        // suspend on the stop confirmation despite the parent's CancellationException.
        withContext(NonCancellable) {
          log("ServiceDiscoveryMdns", "stop() requested for ${registerServiceInfo.serviceName}")
          service.stop()
          // Wait up to 2s for netServiceDidStop to fire. If we return before mDNSResponder
          // has actually deregistered, the next publish in line will collide and Bonjour
          // will rename it to `name (2)`. 2s is generous; observed teardown is well
          // under that. On timeout we proceed anyway — the mutex prevents an obvious
          // collision and leaving a service "stuck" is no worse than the old behaviour.
          val stopped = withTimeoutOrNull(2.seconds) { delegate.awaitStopped() }
          log(
            "ServiceDiscoveryMdns",
            if (stopped != null) "stop confirmed for ${registerServiceInfo.serviceName}"
            else "stop TIMED OUT for ${registerServiceInfo.serviceName} — releasing publish lock anyway",
          )
          service.delegate = null
          serviceReferencesHolder.removeAll { ref -> ref === service }
        }
      }
    }
  }

  private fun createTXTRecordData(attributes: Map<String, String>): NSData {
    val txtRecordData = NSMutableData()
    attributes.forEach { (key, value) ->

      val record = "$key=$value"

      txtRecordData.appendData(byteArrayOf(record.length.toByte()).toNSData())
      txtRecordData.appendData(record.encodeToByteArray().toNSData())
    }

    return txtRecordData.copy() as NSData
  }

  private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(
      bytes = allocArrayOf(this@toNSData),
      length = this@toNSData.size.toULong()
    )
  }

  private fun NSData.toByteArray(): ByteArray {
    return bytes?.readBytes(length.toInt()) ?: ByteArray(0)
  }


  private fun NSNetService.toServiceInfo(): ServiceInfo {

    val txtRecord = this.TXTRecordData()?.toByteArray() ?: ByteArray(0)
    val attributes = txtByteToMap(txtRecord)

    val addresses = (addresses ?: emptyList<NSData>())
      .map { (it as NSData).toByteArray() }
      .filter { it.size == 16 }
      .map { it.copyOfRange(4, 8).map { it.toUByte().toInt() }.joinToString(separator = ".") }

    return ServiceInfo(
      port = this.port.toInt(),
      serviceName = this.name,
      serviceType = this.type,
      attributes = attributes,
      addresses = addresses
    )
  }

  /**
   * Lightweight delegate for the publish path. We only care about the publish/stop
   * lifecycle here so we can serialize cleanly — resolution events go through
   * NetServiceDelegate (browser side). Tracks completion of stop() so registerService
   * can wait for mDNSResponder to actually deregister before allowing the next publish.
   */
  private inner class PublishDelegate(private val instanceName: String) : NSObject(),
    NSNetServiceDelegateProtocol {

    private val stopped = CompletableDeferred<Unit>()

    suspend fun awaitStopped() = stopped.await()

    override fun netServiceWillPublish(sender: NSNetService) {
      log("ServiceDiscoveryMdns", "willPublish[$instanceName]")
    }

    override fun netServiceDidPublish(sender: NSNetService) {
      log("ServiceDiscoveryMdns", "didPublish[$instanceName] as ${sender.name}")
    }

    @ObjCSignatureOverride
    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
      log("ServiceDiscoveryMdns", "didNotPublish[$instanceName] errors=$didNotPublish")
      // Treat publish-failure as immediate stop so we don't hang the publish lock for 2s.
      stopped.complete(Unit)
    }

    override fun netServiceDidStop(sender: NSNetService) {
      log("ServiceDiscoveryMdns", "didStop[$instanceName]")
      stopped.complete(Unit)
    }
  }

  inner class NetServiceDelegate(private val producerScope: ProducerScope<ServiceDiscoveryEvent>) : NSObject(),
    NSNetServiceDelegateProtocol {

      @ObjCSignatureOverride
      override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
      log("ServiceDiscoveryMdns","netService didNotPublish $sender")
    }

    override fun netService(sender: NSNetService, didAcceptConnectionWithInputStream: NSInputStream, outputStream: NSOutputStream) {
      log("ServiceDiscoveryMdns","netService didAcceptConnectionWithInputStream $sender")
    }

    @ObjCSignatureOverride
    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
      log("ServiceDiscoveryMdns","netService didNotResolve $sender")
    }

    override fun netService(sender: NSNetService, didUpdateTXTRecordData: NSData) {
      log("ServiceDiscoveryMdns","netService didUpdateTXTRecordData $sender: ${txtByteToMap(didUpdateTXTRecordData.toByteArray())}")
    }

    override fun netServiceDidPublish(sender: NSNetService) {
      log("ServiceDiscoveryMdns","netServiceDidPublish $sender")
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
      log("ServiceDiscoveryMdns","netServiceDidResolveAddress ${sender.toServiceInfo()}")
      producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(sender.toServiceInfo()))
    }

    override fun netServiceDidStop(sender: NSNetService) {
      log("ServiceDiscoveryMdns","netServiceDidStop $sender")
    }

    override fun netServiceWillPublish(sender: NSNetService) {
      log("ServiceDiscoveryMdns","netServiceWillPublish $sender")
    }

    override fun netServiceWillResolve(sender: NSNetService) {
      log("ServiceDiscoveryMdns","netServiceWillResolve $sender")
    }
  }

  inner class BonjourBrowserDelegate(
    private val producerScope: ProducerScope<ServiceDiscoveryEvent>,
    private val serviceDelegate: NetServiceDelegate
  ) : NSObject(),
    NSNetServiceBrowserDelegateProtocol {

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
      log("ServiceDiscoveryMdns","Bonjour discovery started")
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
      log("ServiceDiscoveryMdns","netServiceBrowser found service: $didFindService - (${didFindService.toServiceInfo()})")

      if (didFindService.addresses.isNullOrEmpty()) {
        log("ServiceDiscoveryMdns","netServiceBrowser resolving service: $didFindService")
        didFindService.delegate = serviceDelegate
        didFindService.resolveWithTimeout(10.0)
      } else {
        producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(didFindService.toServiceInfo()))
      }


    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindDomain: String, moreComing: Boolean) {
      log("ServiceDiscoveryMdns","netServiceBrowser found didFindDomain: $didFindDomain")
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
      browser: NSNetServiceBrowser,
      didRemoveService: NSNetService,
      moreComing: Boolean
    ) {
      log("ServiceDiscoveryMdns","netServiceBrowser didRemoveService: $didRemoveService")
      producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(didRemoveService.toServiceInfo()))
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(
      browser: NSNetServiceBrowser,
      didRemoveDomain: String,
      moreComing: Boolean
    ) {
      log("ServiceDiscoveryMdns","netServiceBrowser didRemoveDomain: $didRemoveDomain")
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
      log("ServiceDiscoveryMdns","netServiceBrowser didNotSearch: $didNotSearch")
    }

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
      log("ServiceDiscoveryMdns","netServiceBrowserDidStopSearch")
    }


  }

}

