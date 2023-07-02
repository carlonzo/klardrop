package com.carlom.klardrop.common.mdns

import kotlinx.cinterop.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy

actual class ServiceDiscoveryMdns {

  private val browserReferencesHolder = mutableListOf<NSNetServiceBrowser>()
  private val browserDelegateReferencesHolder = mutableListOf<BonjourBrowserDelegate>()
  private val serviceReferencesHolder = mutableListOf<NSNetService>()

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> = callbackFlow {

    val browser = NSNetServiceBrowser()
    val delegate = BonjourBrowserDelegate(this)

    browserReferencesHolder.add(browser)
    browserDelegateReferencesHolder.add(delegate)

    browser.delegate = delegate
    browser.includesPeerToPeer = true
    browser.scheduleInRunLoop(NSRunLoop.currentRunLoop(), NSDefaultRunLoopMode)

    browser.searchForServicesOfType(serviceType, inDomain = "local.")

    awaitClose {
      println("closing browser for $serviceType")
      browser.stop()
      browser.delegate = null
      browser.removeFromRunLoop(NSRunLoop.currentRunLoop(), NSDefaultRunLoopMode)
      browserReferencesHolder.removeAll { it === browser }
      browserDelegateReferencesHolder.removeAll { it === delegate }
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
      service.delegate = BonjourServiceDelegate()

      service.publish()

      it.invokeOnCancellation {
        println("closing service publishing for ${registerServiceInfo.serviceName}")
        service.stop()
        service.delegate = null
        serviceReferencesHolder.removeAll { ref -> ref === service }
      }
    }

  }

  private inner class BonjourBrowserDelegate(private val producerScope: ProducerScope<ServiceDiscoveryEvent>) : NSObject(),
    NSNetServiceBrowserDelegateProtocol {

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
      println("Bonjour discovery started")
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
      println("netServiceBrowser remove service: $didRemoveService")

      producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(didRemoveService.toServiceInfo()))
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
      println("netServiceBrowser found service: $didFindService")

      producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(didFindService.toServiceInfo()))
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindDomain: String, moreComing: Boolean) {
      println("Bonjour found domain: $didFindDomain")
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveDomain: String, moreComing: Boolean) {
      println("Bonjour removed domain: $didRemoveDomain")
    }

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
      println("Bonjour discovery stopped")
    }

    override fun netServiceBrowser(
      browser: NSNetServiceBrowser,
      didNotSearch: Map<Any?, *>
    ) {
      println("Bonjour discovery error: ${didNotSearch}")
    }
  }

  private class BonjourServiceDelegate : NSObject(), NSNetServiceDelegateProtocol {
    override fun netServiceWillPublish(sender: NSNetService) {
      println("Service publishing started $sender")
    }

    override fun netServiceDidPublish(sender: NSNetService) {
      println("Service published $sender")
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
      println("Service publishing failed didNotPublish $sender")
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
      println("Service publishing failed didNotResolve $sender")
    }

    override fun netServiceDidStop(sender: NSNetService) {
      println("Service publishing stopped $sender")
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

    return ServiceInfo(
      port = this.port.toInt(),
      serviceName = this.name,
      serviceType = this.type,
      attributes = attributes,
      addresses = this.addresses?.map { it.toString() } ?: emptyList()
    )
  }

}