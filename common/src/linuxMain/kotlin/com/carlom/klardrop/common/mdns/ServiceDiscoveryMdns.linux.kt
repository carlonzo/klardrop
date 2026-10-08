@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.mdns.avahi.AVAHI_ADDRESS_STR_MAX
import com.carlom.klardrop.common.mdns.avahi.AVAHI_IF_UNSPEC
import com.carlom.klardrop.common.mdns.avahi.AVAHI_PROTO_INET
import com.carlom.klardrop.common.mdns.avahi.AVAHI_PROTO_UNSPEC
import com.carlom.klardrop.common.mdns.avahi.AvahiAddress
import com.carlom.klardrop.common.mdns.avahi.AvahiBrowserEvent
import cnames.structs.AvahiClient
import cnames.structs.AvahiEntryGroup
import com.carlom.klardrop.common.mdns.avahi.AvahiEntryGroupState
import com.carlom.klardrop.common.mdns.avahi.AvahiLookupResultFlags
import com.carlom.klardrop.common.mdns.avahi.AvahiResolverEvent
import cnames.structs.AvahiServiceBrowser
import cnames.structs.AvahiServiceResolver
import com.carlom.klardrop.common.mdns.avahi.AvahiStringList
import cnames.structs.AvahiThreadedPoll
import com.carlom.klardrop.common.mdns.avahi.avahi_address_snprint
import com.carlom.klardrop.common.mdns.avahi.avahi_client_free
import com.carlom.klardrop.common.mdns.avahi.avahi_client_new
import com.carlom.klardrop.common.mdns.avahi.avahi_entry_group_add_service_strlst
import com.carlom.klardrop.common.mdns.avahi.avahi_entry_group_commit
import com.carlom.klardrop.common.mdns.avahi.avahi_entry_group_free
import com.carlom.klardrop.common.mdns.avahi.avahi_entry_group_new
import com.carlom.klardrop.common.mdns.avahi.avahi_entry_group_reset
import com.carlom.klardrop.common.mdns.avahi.avahi_service_browser_free
import com.carlom.klardrop.common.mdns.avahi.avahi_service_browser_new
import com.carlom.klardrop.common.mdns.avahi.avahi_service_resolver_free
import com.carlom.klardrop.common.mdns.avahi.avahi_service_resolver_new
import com.carlom.klardrop.common.mdns.avahi.avahi_string_list_add_pair
import com.carlom.klardrop.common.mdns.avahi.avahi_string_list_free
import com.carlom.klardrop.common.mdns.avahi.avahi_string_list_get_next
import com.carlom.klardrop.common.mdns.avahi.avahi_string_list_get_size
import com.carlom.klardrop.common.mdns.avahi.avahi_string_list_get_text
import com.carlom.klardrop.common.mdns.avahi.avahi_threaded_poll_free
import com.carlom.klardrop.common.mdns.avahi.avahi_threaded_poll_get
import com.carlom.klardrop.common.mdns.avahi.avahi_threaded_poll_lock
import com.carlom.klardrop.common.mdns.avahi.avahi_threaded_poll_new
import com.carlom.klardrop.common.mdns.avahi.avahi_threaded_poll_start
import com.carlom.klardrop.common.mdns.avahi.avahi_threaded_poll_stop
import com.carlom.klardrop.common.mdns.avahi.avahi_threaded_poll_unlock
import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

actual class ServiceDiscoveryMdns {

  private val publishMutexByServiceType = mutableMapOf<String, Mutex>()
  private val mutexMapMutex = Mutex()

  private suspend fun publishMutexFor(serviceType: String): Mutex =
    mutexMapMutex.withLock {
      val key = serviceType.removeSuffix(".local.").removeSuffix(".")
      publishMutexByServiceType.getOrPut(key) { Mutex() }
    }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> = callbackFlow {
    val cleanType = serviceType.removeSuffix(".local.").removeSuffix(".")
    val poll = avahi_threaded_poll_new() ?: run {
      close(IllegalStateException("Failed to create avahi threaded poll"))
      return@callbackFlow
    }

    avahi_threaded_poll_lock(poll)
    val client = memScoped {
      val error = alloc<IntVar>()
      avahi_client_new(
        avahi_threaded_poll_get(poll),
        0u,
        null,
        null,
        error.ptr,
      )
    }

    if (client == null) {
      avahi_threaded_poll_unlock(poll)
      avahi_threaded_poll_free(poll)
      log("ServiceDiscoveryMdns", "avahi_client_new failed, avahi daemon likely not running")
      close(IllegalStateException("Avahi daemon not available"))
      return@callbackFlow
    }

    val browserSession = LinuxMdnsBrowserSession(
      client = client,
      poll = poll,
      producer = this,
      cleanServiceType = cleanType,
    )
    val sessionRef = StableRef.create(browserSession)
    browserSession.selfRef = sessionRef

    val browser = avahi_service_browser_new(
      client,
      AVAHI_IF_UNSPEC,
      AVAHI_PROTO_UNSPEC,
      cleanType,
      null,
      0u,
      staticBrowserCallback,
      sessionRef.asCPointer(),
    )

    if (browser == null) {
      sessionRef.dispose()
      avahi_client_free(client)
      avahi_threaded_poll_unlock(poll)
      avahi_threaded_poll_free(poll)
      close(IllegalStateException("Failed to create avahi service browser for $cleanType"))
      return@callbackFlow
    }
    browserSession.browser = browser

    avahi_threaded_poll_unlock(poll)
    avahi_threaded_poll_start(poll)
    log("ServiceDiscoveryMdns", "Avahi discovery started for $cleanType")

    awaitClose {
      log("ServiceDiscoveryMdns", "closing browser for $cleanType")
      avahi_threaded_poll_lock(poll)
      try {
        browserSession.cleanupUnderLock()
      } finally {
        avahi_threaded_poll_unlock(poll)
      }
      avahi_threaded_poll_stop(poll)
      avahi_threaded_poll_free(poll)
    }
  }

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {
    publishMutexFor(registerServiceInfo.serviceType).withLock {
      val cleanType = registerServiceInfo.serviceType.removeSuffix(".local.").removeSuffix(".")
      val poll = avahi_threaded_poll_new() ?: throw IllegalStateException("Failed to create avahi threaded poll")

      avahi_threaded_poll_lock(poll)
      val client = memScoped {
        val error = alloc<IntVar>()
        avahi_client_new(
          avahi_threaded_poll_get(poll),
          0u,
          null,
          null,
          error.ptr,
        )
      }

      if (client == null) {
        avahi_threaded_poll_unlock(poll)
        avahi_threaded_poll_free(poll)
        throw IllegalStateException("Avahi daemon not available")
      }

      val listener = LinuxEntryGroupListener(registerServiceInfo.serviceName)
      val listenerRef = StableRef.create(listener)
      val group = avahi_entry_group_new(client, staticEntryGroupCallback, listenerRef.asCPointer())

      if (group == null) {
        listenerRef.dispose()
        avahi_client_free(client)
        avahi_threaded_poll_unlock(poll)
        avahi_threaded_poll_free(poll)
        throw IllegalStateException("Failed to create avahi entry group")
      }

      var txtList: CPointer<AvahiStringList>? = null
      for ((key, value) in registerServiceInfo.attributes) {
        txtList = avahi_string_list_add_pair(txtList, key, value)
      }

      val ret = avahi_entry_group_add_service_strlst(
        group,
        AVAHI_IF_UNSPEC,
        AVAHI_PROTO_INET,
        0u,
        registerServiceInfo.serviceName,
        cleanType,
        null,
        null,
        registerServiceInfo.port.toUShort(),
        txtList,
      )
      if (txtList != null) {
        avahi_string_list_free(txtList)
      }

      if (ret < 0) {
        avahi_entry_group_free(group)
        listenerRef.dispose()
        avahi_client_free(client)
        avahi_threaded_poll_unlock(poll)
        avahi_threaded_poll_free(poll)
        throw IllegalStateException("avahi_entry_group_add_service_strlst failed with error $ret")
      }

      avahi_entry_group_commit(group)
      avahi_threaded_poll_unlock(poll)
      avahi_threaded_poll_start(poll)
      log("ServiceDiscoveryMdns", "Avahi publish() committed for ${registerServiceInfo.serviceName}")

      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) {
          log("ServiceDiscoveryMdns", "tearing down Avahi publish for ${registerServiceInfo.serviceName}")
          avahi_threaded_poll_lock(poll)
          try {
            avahi_entry_group_reset(group)
            avahi_entry_group_free(group)
            listenerRef.dispose()
            avahi_client_free(client)
          } finally {
            avahi_threaded_poll_unlock(poll)
          }
          avahi_threaded_poll_stop(poll)
          avahi_threaded_poll_free(poll)
        }
      }
    }
  }

  actual suspend fun restart() {
    log("ServiceDiscoveryMdns", "restart: no-op on Linux (Avahi daemon handles transitions natively)")
  }
}

private class LinuxEntryGroupListener(val serviceName: String) {
  fun onStateChange(group: CPointer<AvahiEntryGroup>?, state: AvahiEntryGroupState) {
    log("ServiceDiscoveryMdns", "EntryGroup state changed to $state for $serviceName")
  }
}

private class LinuxMdnsResolverContext(
  val session: LinuxMdnsBrowserSession,
  val serviceName: String,
  val serviceType: String,
  val domain: String?,
)

private class LinuxMdnsBrowserSession(
  val client: CPointer<AvahiClient>,
  val poll: CPointer<AvahiThreadedPoll>,
  val producer: ProducerScope<ServiceDiscoveryEvent>,
  val cleanServiceType: String,
) {
  var selfRef: StableRef<LinuxMdnsBrowserSession>? = null
  var browser: CPointer<AvahiServiceBrowser>? = null
  val knownServices = mutableMapOf<String, ServiceInfo>()
  val activeResolvers = mutableMapOf<CPointer<AvahiServiceResolver>, StableRef<LinuxMdnsResolverContext>>()

  fun cleanupUnderLock() {
    for ((resolver, ref) in activeResolvers) {
      avahi_service_resolver_free(resolver)
      ref.dispose()
    }
    activeResolvers.clear()
    if (browser != null) {
      avahi_service_browser_free(browser)
      browser = null
    }
    selfRef?.dispose()
    selfRef = null
    avahi_client_free(client)
  }

  fun onBrowserEvent(
    interfaceIndex: Int,
    protocol: Int,
    event: AvahiBrowserEvent,
    name: String?,
    type: String?,
    domain: String?,
  ) {
    if (name == null) return
    when (event) {
      AvahiBrowserEvent.AVAHI_BROWSER_NEW -> {
        val resolverCtx = LinuxMdnsResolverContext(this, name, type ?: cleanServiceType, domain)
        val resolverRef = StableRef.create(resolverCtx)
        val resolver = avahi_service_resolver_new(
          client,
          interfaceIndex,
          protocol,
          name,
          type ?: cleanServiceType,
          domain,
          AVAHI_PROTO_INET,
          0u,
          staticResolverCallback,
          resolverRef.asCPointer(),
        )
        if (resolver != null) {
          activeResolvers[resolver] = resolverRef
        } else {
          resolverRef.dispose()
        }
      }
      AvahiBrowserEvent.AVAHI_BROWSER_REMOVE -> {
        val cached = knownServices.remove(name)
        val info = cached ?: ServiceInfo(
          port = 0,
          serviceName = name,
          serviceType = cleanServiceType,
          attributes = emptyMap(),
          addresses = emptyList(),
        )
        producer.trySend(ServiceDiscoveryEvent.ServiceLost(info))
      }
      else -> Unit
    }
  }

  fun onResolverEvent(
    resolver: CPointer<AvahiServiceResolver>?,
    event: AvahiResolverEvent,
    name: String?,
    type: String?,
    domain: String?,
    hostName: String?,
    address: CPointer<AvahiAddress>?,
    port: Int,
    txt: CPointer<AvahiStringList>?,
  ) {
    if (event == AvahiResolverEvent.AVAHI_RESOLVER_FOUND && name != null) {
      val ipStr = if (address != null) {
        memScoped {
          val buf = allocArray<ByteVar>(AVAHI_ADDRESS_STR_MAX)
          avahi_address_snprint(buf, AVAHI_ADDRESS_STR_MAX.toULong(), address)
          buf.toKString()
        }
      } else null

      val attributes = mutableMapOf<String, String>()
      var curr = txt
      while (curr != null) {
        val textPtr = avahi_string_list_get_text(curr)
        val size = avahi_string_list_get_size(curr).toInt()
        if (textPtr != null && size > 0) {
          val bytes = textPtr.readBytes(size)
          val entry = bytes.decodeToString()
          val sep = entry.indexOf('=')
          if (sep > 0) {
            val k = entry.substring(0, sep)
            val v = entry.substring(sep + 1)
            attributes[k] = v
          }
        }
        curr = avahi_string_list_get_next(curr)
      }

      val info = ServiceInfo(
        port = port,
        serviceName = name,
        serviceType = type ?: cleanServiceType,
        attributes = attributes,
        addresses = if (ipStr != null) listOf(ipStr) else emptyList(),
      )
      knownServices[name] = info
      producer.trySend(ServiceDiscoveryEvent.ServiceFound(info))
    }

    if (resolver != null) {
      val ref = activeResolvers.remove(resolver)
      avahi_service_resolver_free(resolver)
      ref?.dispose()
    }
  }
}

private val staticBrowserCallback = staticCFunction<
  CPointer<AvahiServiceBrowser>?,
  Int,
  Int,
  AvahiBrowserEvent,
  CPointer<ByteVar>?,
  CPointer<ByteVar>?,
  CPointer<ByteVar>?,
  AvahiLookupResultFlags,
  COpaquePointer?,
  Unit,
> { _, interfaceIndex, protocol, event, name, type, domain, _, userdata ->
  if (userdata != null) {
    val session = userdata.asStableRef<LinuxMdnsBrowserSession>().get()
    session.onBrowserEvent(
      interfaceIndex = interfaceIndex,
      protocol = protocol,
      event = event,
      name = name?.toKString(),
      type = type?.toKString(),
      domain = domain?.toKString(),
    )
  }
}

private val staticResolverCallback = staticCFunction<
  CPointer<AvahiServiceResolver>?,
  Int,
  Int,
  AvahiResolverEvent,
  CPointer<ByteVar>?,
  CPointer<ByteVar>?,
  CPointer<ByteVar>?,
  CPointer<ByteVar>?,
  CPointer<AvahiAddress>?,
  UShort,
  CPointer<AvahiStringList>?,
  AvahiLookupResultFlags,
  COpaquePointer?,
  Unit,
> { resolver, _, _, event, name, type, domain, hostName, address, port, txt, _, userdata ->
  if (userdata != null) {
    val ctx = userdata.asStableRef<LinuxMdnsResolverContext>().get()
    ctx.session.onResolverEvent(
      resolver = resolver,
      event = event,
      name = name?.toKString(),
      type = type?.toKString(),
      domain = domain?.toKString(),
      hostName = hostName?.toKString(),
      address = address,
      port = port.toInt(),
      txt = txt,
    )
  }
}

private val staticEntryGroupCallback = staticCFunction<
  CPointer<AvahiEntryGroup>?,
  AvahiEntryGroupState,
  COpaquePointer?,
  Unit,
> { group, state, userdata ->
  if (userdata != null) {
    val listener = userdata.asStableRef<LinuxEntryGroupListener>().get()
    listener.onStateChange(group, state)
  }
}
