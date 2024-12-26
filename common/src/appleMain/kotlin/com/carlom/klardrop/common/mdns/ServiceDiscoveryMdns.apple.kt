package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
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

actual class ServiceDiscoveryMdns {

  private val browserReferencesHolder = mutableListOf<NSNetServiceBrowser>()
  private val browserDelegateReferencesHolder = mutableListOf<BonjourBrowserDelegate>()
  private val serviceReferencesHolder = mutableListOf<NSNetService>()


  actual fun discoverServices(serviceType: String) = callbackFlow {

    val browser = NSNetServiceBrowser()
    val serviceDelegate = NetServiceDelegate(this)
    val delegate = BonjourBrowserDelegate(this, serviceDelegate)

    browserReferencesHolder.add(browser)
    browserDelegateReferencesHolder.add(delegate)

    browser.delegate = delegate
    browser.includesPeerToPeer = true

    browser.scheduleInRunLoop(mainRunLoop, NSDefaultRunLoopMode)

    browser.searchForServicesOfType(type = serviceType, inDomain = "")

    log("ServiceDiscoveryMdns","Bonjour discovery started for $serviceType")


    awaitClose {
      log("ServiceDiscoveryMdns","closing browser for $serviceType")
      browser.removeFromRunLoop(mainRunLoop, NSDefaultRunLoopMode)
      browser.stop()
      browser.delegate = null
      browserReferencesHolder.removeAll { ref -> ref === browser }
      browserDelegateReferencesHolder.removeAll { ref -> ref === delegate }
    }

  }

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {

    suspendCancellableCoroutine<Unit> {
      val service = NSNetService(
        domain = "local.",
        type = registerServiceInfo.serviceType,
        name = registerServiceInfo.serviceName,
        port = registerServiceInfo.port
      )

      serviceReferencesHolder.add(service)

      // Set the TXT record on the service
      service.setTXTRecordData(createTXTRecordData(registerServiceInfo.attributes))
      service.includesPeerToPeer = true

      service.publish()

      it.invokeOnCancellation {
        log("ServiceDiscoveryMdns","closing service publishing for ${registerServiceInfo.serviceName}")
        service.stop()
        service.delegate = null
        serviceReferencesHolder.removeAll { ref -> ref === service }
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

