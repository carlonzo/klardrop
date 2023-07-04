package com.carlom.klardrop.common.mdns

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

  //  private val browserReferencesHolder = mutableListOf<NSNetServiceBrowser>()
//  private val browserDelegateReferencesHolder = mutableListOf<BonjourBrowserDelegate>()
  private val serviceReferencesHolder = mutableListOf<NSNetService>()
  var browser: NSNetServiceBrowser? = null
  var delegate: BonjourBrowserDelegate? = null
  var serviceDelegate: NetServiceDelegate? = null

  actual fun discoverServices(serviceType: String) = callbackFlow<ServiceDiscoveryEvent> {


    browser = NSNetServiceBrowser()
    delegate = BonjourBrowserDelegate(this)
    serviceDelegate = NetServiceDelegate(this)

    browser?.delegate = delegate
    browser?.includesPeerToPeer = true

    browser?.scheduleInRunLoop(mainRunLoop, NSDefaultRunLoopMode)

    browser?.searchForServicesOfType(type = serviceType, inDomain = "")

    println("Bonjour discovery started for $serviceType")


    awaitClose {
      println("closing browser for $serviceType")
//      browser.stop()
//      browser.delegate = null
//      browser.removeFromRunLoop(NSRunLoop.currentRunLoop(), NSDefaultRunLoopMode)
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
//      service.delegate = BonjourServiceDelegate()

      service.publish()

      it.invokeOnCancellation {
        println("closing service publishing for ${registerServiceInfo.serviceName}")
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

    val addresses = (addresses?: emptyList<NSData>())
      .map { (it as NSData).toByteArray() }
      .filter { it.size == 16 }
//      .onEach { println("address: ${it.joinToString()}") }
      .map { it.copyOfRange(4,8).map { it.toInt() }.joinToString(separator = ".") }

    return ServiceInfo(
      port = this.port.toInt(),
      serviceName = this.name,
      serviceType = this.type,
      attributes = attributes,
      addresses = addresses
    )
  }

  inner class NetServiceDelegate(private val producerScope: ProducerScope<ServiceDiscoveryEvent>) : NSObject(), NSNetServiceDelegateProtocol{
    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
      println("netService didNotPublish $sender")
    }

    override fun netService(sender: NSNetService, didAcceptConnectionWithInputStream: NSInputStream, outputStream: NSOutputStream) {
      println("netService didAcceptConnectionWithInputStream $sender")
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
      println("netService didNotResolve $sender")
    }

    override fun netService(sender: NSNetService, didUpdateTXTRecordData: NSData) {
      println("netService didUpdateTXTRecordData $sender: ${txtByteToMap(didUpdateTXTRecordData.toByteArray())}")
    }

    override fun netServiceDidPublish(sender: NSNetService) {
     println("netServiceDidPublish $sender")
    }

    override fun netServiceDidResolveAddress(sender: NSNetService) {
      println("netServiceDidResolveAddress $sender ${sender.toServiceInfo()}")
      producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(sender.toServiceInfo()))
    }

    override fun netServiceDidStop(sender: NSNetService) {
      println("netServiceDidStop $sender")
    }

    override fun netServiceWillPublish(sender: NSNetService) {
      println ("netServiceWillPublish $sender")
    }

    override fun netServiceWillResolve(sender: NSNetService) {
      println("netServiceWillResolve $sender")
    }
  }

  inner class BonjourBrowserDelegate(private val producerScope: ProducerScope<ServiceDiscoveryEvent>) : NSObject(),
    NSNetServiceBrowserDelegateProtocol {

    override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
      println("Bonjour discovery started")
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
      println("netServiceBrowser found service: $didFindService - (${didFindService.toServiceInfo()})")

      if (didFindService.addresses.isNullOrEmpty()) {
        println("netServiceBrowser resolving service: $didFindService")
        didFindService.delegate = serviceDelegate
        didFindService.resolveWithTimeout(10.0)
      } else {
        producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(didFindService.toServiceInfo()))
      }


    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindDomain: String, moreComing: Boolean) {
      println("netServiceBrowser found didFindDomain: $didFindDomain")
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(
      browser: platform.Foundation.NSNetServiceBrowser,
      didRemoveService: platform.Foundation.NSNetService,
      moreComing: kotlin.Boolean
    ) {
      println("netServiceBrowser didRemoveService: $didRemoveService")
      producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(didRemoveService.toServiceInfo()))
    }

    @Suppress("CONFLICTING_OVERLOADS", "PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun netServiceBrowser(
      browser: platform.Foundation.NSNetServiceBrowser,
      didRemoveDomain: kotlin.String,
      moreComing: kotlin.Boolean
    ) {
      println("netServiceBrowser didRemoveDomain: $didRemoveDomain")
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
      println("netServiceBrowser didNotSearch: $didNotSearch")
    }

    override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
      println("netServiceBrowserDidStopSearch")
    }


  }

}

