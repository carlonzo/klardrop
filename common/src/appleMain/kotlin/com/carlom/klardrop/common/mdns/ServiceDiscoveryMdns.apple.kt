package com.carlom.klardrop.common.mdns

import kotlinx.cinterop.*
import kotlinx.coroutines.flow.Flow

import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.*
import platform.darwin.NSObject

actual class ServiceDiscoveryMdns {

  actual fun discoverServices(serviceType: String): Flow<List<ServiceInfo>> {
    val browser = NSNetServiceBrowser()

    val delegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
      override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
        println("Bonjour discovery started")
      }

      override fun netServiceBrowser(
        browser: NSNetServiceBrowser,
        didFindService: NSNetService,
        moreComing: Boolean
      ) {
        println("Service discovered: ${didFindService.name}")
//        onServiceDiscovered(didFindService.name)
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

    browser.delegate = delegate
    browser.searchForServicesOfType(serviceType, inDomain = "")

//    // Keep the delegate object alive
//    val delegatePtr = delegate.asCPointer()
//    val objCObjectVar: ObjCObjectVar<NSObject> = delegatePtr.readValue()
//    objCObjectVar.init(delegatePtr)
    return emptyFlow()
  }

  actual suspend fun registerService(serviceInfo: ServiceInfo) {

    val service = NSNetService(domain = "", type = serviceInfo.serviceType, name = serviceInfo.serviceName, port = serviceInfo.port)

    // Set the TXT record on the service
    service.setTXTRecordData(createTXTRecordData(serviceInfo.attributes))

    service.delegate = BonjourServiceDelegate()

    service.publish()

  }

  private class BonjourServiceDelegate : NSObject(), NSNetServiceDelegateProtocol {
    override fun netServiceWillPublish(sender: NSNetService) {
      println("Service publishing started $sender")
    }

    override fun netServiceDidPublish(sender: NSNetService) {
      println("Service published $sender")
    }

    override fun netService(sender: NSNetService, didNotPublish: Map<Any?, *>) {
      println("Service publishing failed $sender")
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
    NSData.create(bytes = allocArrayOf(this@toNSData),
      length = this@toNSData.size.toULong())
  }




}