package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class BonjourServiceDiscoveryMdns : ServiceDiscoveryMdnsBackend {

  private val library = DnsSdLibrary.INSTANCE
  private val posix = PosixCLibrary.INSTANCE

  override fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> = callbackFlow {
    val regtype = serviceType.removeSuffix(".local.").removeSuffix(".")
    val domain = "local."

    val pendingLoops = Collections.newSetFromMap(ConcurrentHashMap<BonjourPollLoop, Boolean>())
    val knownServices = ConcurrentHashMap<String, ServiceInfo>()

    val browseCallback = DnsSdLibrary.BrowseCallback { _, flags, interfaceIndex, errorCode, serviceName, foundRegtype, replyDomain, _ ->
      if (errorCode != DnsSdLibrary.ERR_NO_ERROR) {
        log("BonjourMdns", "browse error: $errorCode")
        return@BrowseCallback
      }
      if (serviceName == null || foundRegtype == null || replyDomain == null) return@BrowseCallback

      val isAdd = (flags and DnsSdLibrary.FLAG_ADD) != 0
      if (isAdd) {
        startResolve(this, interfaceIndex, serviceName, foundRegtype, replyDomain, knownServices, pendingLoops)
      } else {
        val cached = knownServices.remove(serviceName)
        val info = cached ?: ServiceInfo(
          port = 0,
          serviceName = serviceName,
          serviceType = foundRegtype,
          attributes = emptyMap(),
          addresses = emptyList(),
        )
        trySend(ServiceDiscoveryEvent.ServiceLost(info))
      }
    }

    val sdRefHolder = PointerByReference()
    val err = library.DNSServiceBrowse(
      sdRefHolder,
      0,
      0,
      regtype,
      domain,
      browseCallback,
      null,
    )

    if (err != DnsSdLibrary.ERR_NO_ERROR) {
      log("BonjourMdns", "DNSServiceBrowse failed: $err for $regtype")
      close(IllegalStateException("DNSServiceBrowse failed: $err"))
      return@callbackFlow
    }

    log("BonjourMdns", "Bonjour browse started for $regtype in $domain")

    val browseLoop = BonjourPollLoop(library, posix, sdRefHolder.value, "Bonjour-Browse-$regtype")
    browseLoop.holdReference(browseCallback)
    browseLoop.start()

    awaitClose {
      log("BonjourMdns", "closing Bonjour browse for $regtype")
      browseLoop.stop()
      pendingLoops.toList().forEach { it.stop() }
      pendingLoops.clear()
      knownServices.clear()
    }
  }

  private fun startResolve(
    producer: ProducerScope<ServiceDiscoveryEvent>,
    interfaceIndex: Int,
    serviceName: String,
    regtype: String,
    domain: String,
    knownServices: ConcurrentHashMap<String, ServiceInfo>,
    pendingLoops: MutableSet<BonjourPollLoop>,
  ) {
    val resolveRef = PointerByReference()
    val resolveLoopHolder = arrayOfNulls<BonjourPollLoop>(1)

    val resolveCallback = DnsSdLibrary.ResolveCallback { _, _, addrInterfaceIndex, errorCode, _, hosttarget, port, txtLen, txtRecord, _ ->
      val loop = resolveLoopHolder[0]
      if (errorCode != DnsSdLibrary.ERR_NO_ERROR || hosttarget == null) {
        log("BonjourMdns", "resolve error: $errorCode for $serviceName")
        loop?.stop()
        return@ResolveCallback
      }

      val txtBytes = if (txtRecord != null && (txtLen.toInt() and 0xFFFF) > 0) {
        txtRecord.getByteArray(0, txtLen.toInt() and 0xFFFF)
      } else {
        ByteArray(0)
      }
      val attributes = if (txtBytes.isNotEmpty()) txtByteToMap(txtBytes) else emptyMap()
      val resolvedPort = ntohs(port)

      startAddrInfoResolution(
        producer = producer,
        interfaceIndex = addrInterfaceIndex,
        serviceName = serviceName,
        regtype = regtype,
        port = resolvedPort,
        attributes = attributes,
        hosttarget = hosttarget,
        knownServices = knownServices,
        pendingLoops = pendingLoops,
      )
      // We've got what we need from this resolve; stop the loop and let the
      // address-resolution loop run independently.
      loop?.stop()
    }

    val err = library.DNSServiceResolve(
      resolveRef,
      0,
      interfaceIndex,
      serviceName,
      regtype,
      domain,
      resolveCallback,
      null,
    )

    if (err != DnsSdLibrary.ERR_NO_ERROR) {
      log("BonjourMdns", "DNSServiceResolve failed: $err for $serviceName")
      return
    }

    val loop = BonjourPollLoop(library, posix, resolveRef.value, "Bonjour-Resolve-$serviceName") {
      pendingLoops.removeIf { it === resolveLoopHolder[0] }
    }
    loop.holdReference(resolveCallback)
    resolveLoopHolder[0] = loop
    pendingLoops.add(loop)
    loop.start()
  }

  private fun startAddrInfoResolution(
    producer: ProducerScope<ServiceDiscoveryEvent>,
    interfaceIndex: Int,
    serviceName: String,
    regtype: String,
    port: Int,
    attributes: Map<String, String>,
    hosttarget: String,
    knownServices: ConcurrentHashMap<String, ServiceInfo>,
    pendingLoops: MutableSet<BonjourPollLoop>,
  ) {
    val addrRef = PointerByReference()
    val addrLoopHolder = arrayOfNulls<BonjourPollLoop>(1)
    val collected = mutableListOf<String>()
    val emitted = AtomicBoolean(false)

    val addrCallback = DnsSdLibrary.GetAddrInfoCallback { _, flags, _, errorCode, _, address, _, _ ->
      if (errorCode != DnsSdLibrary.ERR_NO_ERROR) {
        log("BonjourMdns", "getaddrinfo error: $errorCode for $hosttarget")
        addrLoopHolder[0]?.stop()
        return@GetAddrInfoCallback
      }
      if (address != null) {
        readIPv4Address(address)?.let { ipv4 ->
          if (!collected.contains(ipv4)) collected.add(ipv4)
        }
      }
      val moreComing = (flags and DnsSdLibrary.FLAG_MORE_COMING) != 0
      if (!moreComing && collected.isNotEmpty() && emitted.compareAndSet(false, true)) {
        val info = ServiceInfo(
          port = port,
          serviceName = serviceName,
          serviceType = regtype,
          attributes = attributes,
          addresses = collected.toList(),
        )
        knownServices[serviceName] = info
        producer.trySend(ServiceDiscoveryEvent.ServiceFound(info))
        addrLoopHolder[0]?.stop()
      }
    }

    val err = library.DNSServiceGetAddrInfo(
      addrRef,
      0,
      interfaceIndex,
      DnsSdLibrary.PROTOCOL_IPV4,
      hosttarget,
      addrCallback,
      null,
    )

    if (err != DnsSdLibrary.ERR_NO_ERROR) {
      log("BonjourMdns", "DNSServiceGetAddrInfo failed: $err for $hosttarget")
      return
    }

    val loop = BonjourPollLoop(library, posix, addrRef.value, "Bonjour-Addr-$hosttarget") {
      pendingLoops.removeIf { it === addrLoopHolder[0] }
    }
    loop.holdReference(addrCallback)
    addrLoopHolder[0] = loop
    pendingLoops.add(loop)
    loop.start()
  }

  override suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {
    suspendCancellableCoroutine<Unit> { continuation ->
      val txt = encodeTxtRecord(registerServiceInfo.attributes)
      val regtype = registerServiceInfo.serviceType.removeSuffix(".local.").removeSuffix(".")

      val registerCallback = DnsSdLibrary.RegisterCallback { _, _, errorCode, name, _, _, _ ->
        if (errorCode != DnsSdLibrary.ERR_NO_ERROR) {
          log("BonjourMdns", "register error: $errorCode for ${registerServiceInfo.serviceName}")
        } else {
          log("BonjourMdns", "register success: name=$name")
        }
      }

      val sdRef = PointerByReference()
      val err = library.DNSServiceRegister(
        sdRef,
        0,
        0,
        registerServiceInfo.serviceName,
        regtype,
        "local.",
        null,
        htons(registerServiceInfo.port),
        txt.size.toShort(),
        txt.takeIf { it.isNotEmpty() },
        registerCallback,
        null,
      )

      if (err != DnsSdLibrary.ERR_NO_ERROR) {
        log("BonjourMdns", "DNSServiceRegister failed: $err for ${registerServiceInfo.serviceName}")
        return@suspendCancellableCoroutine
      }

      log("BonjourMdns", "publishing service: $registerServiceInfo")

      val loop = BonjourPollLoop(library, posix, sdRef.value, "Bonjour-Register-${registerServiceInfo.serviceName}")
      loop.holdReference(registerCallback)
      loop.start()

      continuation.invokeOnCancellation {
        log("BonjourMdns", "stopping Bonjour register for ${registerServiceInfo.serviceName}")
        loop.stop()
      }
    }
  }

  private fun encodeTxtRecord(attributes: Map<String, String>): ByteArray {
    if (attributes.isEmpty()) return ByteArray(0)
    val out = ByteArrayOutputStream()
    attributes.forEach { (key, value) ->
      val record = "$key=$value".encodeToByteArray()
      require(record.size <= 255) { "TXT record entry too long: $key" }
      out.write(record.size)
      out.write(record)
    }
    return out.toByteArray()
  }

  private fun readIPv4Address(sockaddr: Pointer): String? {
    // macOS sockaddr_in: { uint8_t sa_len, uint8_t sa_family, uint16_t sin_port,
    // uint32_t sin_addr, ... }. AF_INET == 2.
    val family = sockaddr.getByte(1).toInt() and 0xFF
    if (family != 2) return null
    val addrBytes = sockaddr.getByteArray(4, 4)
    return addrBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
  }

  companion object {
    private val isLittleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    fun htons(port: Int): Short {
      val v = port and 0xFFFF
      return if (isLittleEndian) {
        (((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)).toShort()
      } else {
        v.toShort()
      }
    }

    fun ntohs(value: Short): Int {
      val v = value.toInt() and 0xFFFF
      return if (isLittleEndian) {
        ((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)
      } else {
        v
      }
    }
  }

  override suspend fun restart() {
    // libdns_sd talks to the OS-resident mDNSResponder, which already reacts to
    // network transitions (sleep/wake, NIC up/down) on its own. Active browses /
    // registrations stay valid across those transitions, so there's no per-process
    // state to tear down here.
    log("BonjourMdns", "restart: no-op (mDNSResponder handles transitions natively)")
  }
}

internal class BonjourPollLoop(
  private val library: DnsSdLibrary,
  private val posix: PosixCLibrary,
  private val sdRef: Pointer,
  private val threadName: String,
  private val onExit: (Int) -> Unit = {},
) {
  private val stopped = AtomicBoolean(false)
  private val started = AtomicBoolean(false)
  private val callbackRefs = mutableListOf<Any>()
  private val sockFd: Int = library.DNSServiceRefSockFD(sdRef)
  private val pollFd = PollFd().apply {
    fd = sockFd
    events = PosixCLibrary.POLLIN
    write()
  }

  private val thread = Thread({
    var lastError = DnsSdLibrary.ERR_NO_ERROR
    try {
      while (!stopped.get()) {
        val n = posix.poll(pollFd, 1, 250)
        if (stopped.get()) break
        if (n > 0) {
          pollFd.read()
          if ((pollFd.revents.toInt() and PosixCLibrary.POLLIN.toInt()) != 0) {
            val err = library.DNSServiceProcessResult(sdRef)
            if (err != DnsSdLibrary.ERR_NO_ERROR) {
              lastError = err
              break
            }
          }
        }
      }
    } catch (t: Throwable) {
      log("BonjourMdns", "poll loop $threadName threw: ${t.message}")
    } finally {
      try {
        library.DNSServiceRefDeallocate(sdRef)
      } catch (_: Throwable) {
      }
      onExit(lastError)
    }
  }, threadName).apply {
    isDaemon = true
  }

  fun holdReference(ref: Any) {
    callbackRefs.add(ref)
  }

  fun start() {
    if (started.compareAndSet(false, true)) thread.start()
  }

  fun stop() {
    stopped.set(true)
  }
}
