package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.time.Duration.Companion.milliseconds


actual class ServiceDiscoveryMdns {

  // macOS 15+ requires the Local Network privacy permission for any process that wants to
  // RECEIVE local-link multicast (mDNS, SSDP, …). Unsigned JVMs launched via Terminal/Gradle
  // never get prompted, so jmDNS publishes still go out but its inbound socket sees nothing.
  // Strategy on macOS:
  //   1. Publish via jmDNS (always works — outbound multicast isn't blocked).
  //   2. Probe at startup whether jmDNS can also RECEIVE its own multicast announcement.
  //      If yes → keep using jmDNS for browse (notarized app with Local Network permission,
  //      or older macOS). If no → fall back to `dns-sd` subprocess, which talks to
  //      `mDNSResponder` over XPC and isn't gated by the per-process permission.
  //   3. `-Dklardrop.mdns.macos=jmdns|dnssd|auto` overrides the probe.
  // Other JVM platforms (Linux, Windows) keep using jmDNS for both directions.
  private val isMacOs: Boolean =
    System.getProperty("os.name", "").lowercase().contains("mac")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val jmdns by lazy {
    val addresses = getAddresses()
    log("ServiceDiscoveryMdns", "jmDNS binding to addresses: ${addresses.map { it.hostAddress }}")
    addresses.map { JmDNS.create(it, it.hostAddress) }
  }

  // Resolved lazily on first browse; cached for the lifetime of the process.
  private val browseStrategy: kotlinx.coroutines.Deferred<BrowseStrategy> = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
    when {
      !isMacOs -> BrowseStrategy.JMDNS
      strategyOverride() != null -> strategyOverride()!!.also {
        log("ServiceDiscoveryMdns", "macOS browse strategy forced via system property: $it")
      }
      probeJmdnsBrowse() -> {
        log("ServiceDiscoveryMdns", "macOS jmDNS browse probe SUCCEEDED — using jmDNS for browse")
        BrowseStrategy.JMDNS
      }
      else -> {
        log(
          "ServiceDiscoveryMdns",
          "macOS jmDNS browse probe FAILED (likely missing Local Network permission); " +
            "falling back to dns-sd subprocess for browse"
        )
        BrowseStrategy.DNS_SD
      }
    }
  }

  private fun strategyOverride(): BrowseStrategy? =
    when (System.getProperty("klardrop.mdns.macos", "auto").lowercase()) {
      "jmdns" -> BrowseStrategy.JMDNS
      "dnssd", "dns-sd" -> BrowseStrategy.DNS_SD
      else -> null
    }

  private fun getAddresses(): List<Inet4Address> {
    val addresses = mutableListOf<Inet4Address>()
    NetworkInterface.getNetworkInterfaces().iterator().forEach { networkInterface ->
      if (networkInterface.isLoopback) return@forEach
      if (!networkInterface.isUp) return@forEach
      networkInterface.inetAddresses.iterator().forEach { inetAddress ->
        if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
          addresses.add(inetAddress)
        }
      }
    }
    return addresses
  }

  actual fun discoverServices(serviceType: String): Flow<ServiceDiscoveryEvent> = flow {
    // Strategy resolution is awaited inside the flow so we never block the caller's
    // dispatcher thread. The probe is bounded by PROBE_TIMEOUT_MS, so this only adds
    // latency to the first ever discovery call.
    val strategy = browseStrategy.await()
    val inner = when (strategy) {
      BrowseStrategy.JMDNS -> jmdnsDiscover(serviceType)
      BrowseStrategy.DNS_SD -> DnsSdBrowser.browse(serviceType)
    }
    emitAll(inner)
  }

  private fun jmdnsDiscover(serviceType: String): Flow<ServiceDiscoveryEvent> {
    val serviceTypeLocal =
      if (serviceType.endsWith("local.")) serviceType else "${serviceType}local."

    return callbackFlow {
      val listener = createServiceListener(this)
      jmdns.forEach { instance -> instance.addServiceListener(serviceTypeLocal, listener) }
      awaitClose {
        jmdns.forEach { instance ->
          runCatching { instance.removeServiceListener(serviceTypeLocal, listener) }
        }
      }
    }.flowOn(Dispatchers.IO)
  }

  actual suspend fun registerService(registerServiceInfo: RegisterServiceInfo) {
    // Same strategy split as browse: on macOS without Local Network permission, jmDNS's
    // own outbound multicast is also dropped, so peers (including the local mDNSResponder)
    // never see our publish. Route publishes through dns-sd in that case so the OS daemon
    // does the announcing for us.
    val strategy = browseStrategy.await()

    when (strategy) {
      BrowseStrategy.JMDNS -> registerViaJmdns(registerServiceInfo)
      BrowseStrategy.DNS_SD -> DnsSdRegistrar.register(registerServiceInfo)
    }
  }

  private suspend fun registerViaJmdns(registerServiceInfo: RegisterServiceInfo) {
    suspendCancellableCoroutine<Unit> {

      val registrations = jmdns.map { instance ->

        val jmdnsServiceInfo = javax.jmdns.ServiceInfo.create(
          registerServiceInfo.serviceType,
          registerServiceInfo.serviceName,
          registerServiceInfo.port,
          0,
          0,
          registerServiceInfo.attributes
        )

        instance.registerService(jmdnsServiceInfo)

        jmdnsServiceInfo
      }

      log("ServiceDiscoveryMdns", "publishing service: $registerServiceInfo")

      it.invokeOnCancellation {
        registrations.forEach { jmdnsServiceInfo ->
          jmdns.forEach { instance -> instance.unregisterService(jmdnsServiceInfo) }
        }
      }
    }

  }

  /**
   * Publishes a unique short-lived service via jmDNS, then opens a jmDNS browse for the same
   * service type. If we get our own resolution back within [PROBE_TIMEOUT_MS], jmDNS receive
   * is functional. Otherwise inbound multicast is being blocked (typically the macOS 15+
   * Local Network privacy gate against unsigned/un-prompted JVMs).
   */
  private suspend fun probeJmdnsBrowse(): Boolean {
    val probeType = "_klardrop-probe._tcp."
    val probeName = "probe-${UUID.randomUUID().toString().take(8)}"
    val seen = CompletableDeferred<Unit>()

    val instances = try {
      jmdns
    } catch (t: Throwable) {
      log("ServiceDiscoveryMdns", "probe: jmDNS init failed: ${t.message}")
      return false
    }

    val listener = object : ServiceListener {
      override fun serviceAdded(event: ServiceEvent) {}
      override fun serviceRemoved(event: ServiceEvent) {}
      override fun serviceResolved(event: ServiceEvent) {
        if (event.name == probeName && !seen.isCompleted) seen.complete(Unit)
      }
    }

    val typeLocal = "${probeType}local."
    val publishedInfos = mutableListOf<Pair<JmDNS, javax.jmdns.ServiceInfo>>()
    return try {
      instances.forEach { it.addServiceListener(typeLocal, listener) }
      instances.forEach { instance ->
        val info = javax.jmdns.ServiceInfo.create(probeType, probeName, 1, 0, 0, mapOf("p" to "1"))
        instance.registerService(info)
        publishedInfos.add(instance to info)
      }
      withTimeoutOrNull(PROBE_TIMEOUT_MS.milliseconds) { seen.await() } != null
    } catch (t: Throwable) {
      log("ServiceDiscoveryMdns", "probe failed: ${t.message}")
      false
    } finally {
      publishedInfos.forEach { (instance, info) ->
        runCatching { instance.unregisterService(info) }
      }
      instances.forEach { runCatching { it.removeServiceListener(typeLocal, listener) } }
    }
  }

  private fun ServiceEvent.toServiceInfo(): ServiceInfo {
    val attributes = txtByteToMap(this.info.textBytes)

    return ServiceInfo(
      port = this.info.port,
      serviceName = this.info.name,
      serviceType = this.info.type,
      attributes = attributes,
      addresses = this.info.inet4Addresses.map { it.hostAddress }
    )
  }

  private fun createServiceListener(producerScope: ProducerScope<ServiceDiscoveryEvent>): ServiceListener {
    return object : ServiceListener {
      override fun serviceAdded(event: ServiceEvent) {
        log("ServiceDiscoveryMdns", "serviceAdded: type=${event.type} name=${event.name}")
      }

      override fun serviceRemoved(event: ServiceEvent) {
        log("ServiceDiscoveryMdns", "serviceRemoved: ${event.info}")
        producerScope.trySend(ServiceDiscoveryEvent.ServiceLost(event.toServiceInfo()))
      }

      override fun serviceResolved(event: ServiceEvent) {
        log(
          "ServiceDiscoveryMdns",
          "serviceResolved: name=${event.name} addrs=${event.info.inet4Addresses.map { it.hostAddress }} txt=${event.info.propertyNames.toList()}"
        )
        if (event.info.inet4Addresses.isEmpty()) {
          return
        }
        producerScope.trySend(ServiceDiscoveryEvent.ServiceFound(event.toServiceInfo()))
      }

    }

  }

  private companion object {
    const val PROBE_TIMEOUT_MS = 2_500L
  }

}

private enum class BrowseStrategy { JMDNS, DNS_SD }

/**
 * macOS-only fallback that uses `/usr/bin/dns-sd` (which talks to `mDNSResponder` via XPC)
 * instead of opening our own multicast socket. This sidesteps the macOS 15+ Local Network
 * permission gate that silently blocks inbound multicast for unsigned/un-prompted JVMs.
 *
 * One long-lived `dns-sd -B` subprocess feeds Add/Rmv events; each Add spawns a short-lived
 * `dns-sd -L` to fetch hostname/port/TXT, then resolves the hostname to IPv4 via the JVM
 * resolver (which goes through `mDNSResponder` for `*.local.` names).
 */
private object DnsSdBrowser {
  private const val TAG = "ServiceDiscoveryMdns.dnsSd"
  private const val DNS_SD_BIN = "/usr/bin/dns-sd"
  private const val RESOLVE_TIMEOUT_MS = 3_000L
  private const val PROCESS_KILL_TIMEOUT_S = 2L

  fun browse(serviceType: String): Flow<ServiceDiscoveryEvent> {
    val regtype = toRegtype(serviceType)
    val canonicalServiceType = "$regtype.local."

    return callbackFlow {
      val process = try {
        ProcessBuilder(DNS_SD_BIN, "-B", regtype, "local.")
          .redirectErrorStream(true)
          .start()
      } catch (t: Throwable) {
        log(TAG, "failed to spawn `dns-sd -B $regtype`: ${t.message}", t)
        close(t)
        return@callbackFlow
      }

      log(TAG, "browsing $regtype via dns-sd (pid=${process.pid()})")

      // Cache by instance name so `Rmv` can produce a richer ServiceLost matching the
      // ServiceFound we previously emitted.
      val cache = ConcurrentHashMap<String, ServiceInfo>()

      val readerScope = CoroutineScope(coroutineContext + Job())
      val readerJob = readerScope.launch(Dispatchers.IO) {
        try {
          BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { rawLine ->
              val line = rawLine.trimEnd()
              if (!isInstanceLine(line)) return@forEach

              val parsed = parseBrowseLine(line) ?: return@forEach
              val (action, instanceName) = parsed

              when (action) {
                "Add" -> readerScope.launch {
                  val info = resolveInstance(instanceName, regtype, canonicalServiceType)
                  if (info != null) {
                    cache[instanceName] = info
                    trySend(ServiceDiscoveryEvent.ServiceFound(info))
                  }
                }

                "Rmv" -> {
                  val info = cache.remove(instanceName) ?: ServiceInfo(
                    port = 0,
                    serviceName = instanceName,
                    serviceType = canonicalServiceType,
                    attributes = emptyMap(),
                    addresses = emptyList(),
                  )
                  trySend(ServiceDiscoveryEvent.ServiceLost(info))
                }
              }
            }
          }
        } catch (t: Throwable) {
          log(TAG, "browse stdout reader for $regtype ended: ${t.message}")
        }
      }

      awaitClose {
        readerScope.cancel()
        readerJob.cancel()
        runCatching { process.destroy() }
        if (!process.waitFor(PROCESS_KILL_TIMEOUT_S, TimeUnit.SECONDS)) {
          runCatching { process.destroyForcibly() }
        }
      }
    }
  }

  // dns-sd browse output (after the header):
  //   "<ts>  <Add|Rmv>  <flags>  <if>  <Domain>  <ServiceType>  <InstanceName>"
  // Instance names can contain spaces, so we split into the first 6 whitespace-separated
  // columns and treat the remainder as the instance name.
  private fun parseBrowseLine(line: String): Pair<String, String>? {
    val tokens = line.split(Regex("\\s+"), limit = 7)
    if (tokens.size < 7) return null
    val action = tokens[1]
    if (action != "Add" && action != "Rmv") return null
    val instanceName = tokens[6].trim()
    if (instanceName.isEmpty()) return null
    return action to instanceName
  }

  private fun isInstanceLine(line: String): Boolean {
    if (line.isEmpty()) return false
    if (line.startsWith("Browsing")) return false
    if (line.startsWith("DATE:")) return false
    if (line.startsWith("Timestamp")) return false
    if (line.contains("STARTING")) return false
    return true
  }

  private suspend fun resolveInstance(
    instanceName: String,
    regtype: String,
    canonicalServiceType: String,
  ): ServiceInfo? = withContext(Dispatchers.IO) {
    val proc = try {
      ProcessBuilder(DNS_SD_BIN, "-L", instanceName, regtype, "local.")
        .redirectErrorStream(true)
        .start()
    } catch (t: Throwable) {
      log(TAG, "failed to spawn `dns-sd -L $instanceName $regtype`: ${t.message}", t)
      return@withContext null
    }

    try {
      val reader = BufferedReader(InputStreamReader(proc.inputStream))
      var hostname: String? = null
      var port: Int = 0
      var attributes: Map<String, String> = emptyMap()
      val deadline = System.currentTimeMillis() + RESOLVE_TIMEOUT_MS

      while (System.currentTimeMillis() < deadline) {
        val line = reader.readLine()?.trimEnd() ?: break
        val reachIdx = line.indexOf("can be reached at")
        if (reachIdx >= 0) {
          // "<canonical>. can be reached at <host>:<port> (interface N)"
          val rest = line.substring(reachIdx + "can be reached at".length).trim()
          val firstSpace = rest.indexOf(' ')
          val hostPort = if (firstSpace >= 0) rest.substring(0, firstSpace) else rest
          val colon = hostPort.lastIndexOf(':')
          if (colon > 0) {
            hostname = stripTrailingDot(hostPort.substring(0, colon))
            port = hostPort.substring(colon + 1).toIntOrNull() ?: 0
          }
        } else if (hostname != null && line.isNotBlank() &&
          !line.startsWith("DATE:") && !line.contains("STARTING") && !line.startsWith("Lookup")
        ) {
          // The TXT record line follows the reachability line. dns-sd renders it as
          // whitespace-separated `key=value` tokens. Klardrop's TXT values are URL-safe
          // base64 or short ASCII integers — no embedded spaces — so a simple split is fine.
          attributes = parseTxtLine(line)
          break
        }
      }

      if (hostname == null || port <= 0) {
        log(TAG, "resolve($instanceName) returned no host/port within ${RESOLVE_TIMEOUT_MS}ms")
        return@withContext null
      }

      val addresses = runCatching {
        InetAddress.getAllByName(hostname).filterIsInstance<Inet4Address>().map { it.hostAddress }
      }.getOrElse { t ->
        log(TAG, "resolveInstance($instanceName) hostname '$hostname' lookup failed: ${t.message}")
        emptyList()
      }

      ServiceInfo(
        port = port,
        serviceName = instanceName,
        serviceType = canonicalServiceType,
        attributes = attributes,
        addresses = addresses,
      )
    } catch (t: Throwable) {
      log(TAG, "resolveInstance($instanceName) failed: ${t.message}", t)
      null
    } finally {
      runCatching { proc.destroy() }
      if (!proc.waitFor(1, TimeUnit.SECONDS)) {
        runCatching { proc.destroyForcibly() }
      }
    }
  }

  private fun parseTxtLine(line: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    line.split(' ').forEach { token ->
      val trimmed = token.trim()
      if (trimmed.isEmpty()) return@forEach
      val eq = trimmed.indexOf('=')
      if (eq <= 0) return@forEach
      val key = trimmed.substring(0, eq)
      val value = trimmed.substring(eq + 1)
      out[key] = value
    }
    return out
  }

  // Accepts "_klardrop._tcp.", "_klardrop._tcp", "_klardrop._tcp.local.", etc.
  // Returns the regtype suitable for `dns-sd -B/-L <regtype> local.`, e.g. "_klardrop._tcp".
  private fun toRegtype(serviceType: String): String {
    var s = serviceType.trim()
    while (s.endsWith(".")) s = s.dropLast(1)
    if (s.endsWith(".local")) s = s.dropLast(".local".length)
    while (s.endsWith(".")) s = s.dropLast(1)
    return s
  }

  private fun stripTrailingDot(s: String): String =
    if (s.endsWith(".")) s.dropLast(1) else s
}

/**
 * macOS-only registrar. Spawns one long-lived `dns-sd -R` subprocess per published service
 * and keeps it alive for the duration of [register]'s coroutine. When the coroutine is
 * cancelled, the subprocess is killed which causes `mDNSResponder` to withdraw the record.
 *
 * This is the publish counterpart to [DnsSdBrowser]: when the macOS Local Network gate is
 * blocking the JVM's multicast socket, jmDNS' outbound announcements are dropped and other
 * peers (and even the local `mDNSResponder`) never see them. Going through `dns-sd` lets
 * `mDNSResponder` do the announcing on our behalf.
 */
private object DnsSdRegistrar {
  private const val TAG = "ServiceDiscoveryMdns.dnsSd"
  private const val DNS_SD_BIN = "/usr/bin/dns-sd"
  private const val PROCESS_KILL_TIMEOUT_S = 2L

  suspend fun register(info: RegisterServiceInfo) {
    val regtype = info.serviceType.trimEnd('.')
    // dns-sd CLI: -R <name> <type> <domain> <port> [k=v ...]
    val cmd = buildList {
      add(DNS_SD_BIN); add("-R")
      add(info.serviceName)
      add(regtype)
      add("local.")
      add(info.port.toString())
      info.attributes.forEach { (k, v) -> add("$k=$v") }
    }

    suspendCancellableCoroutine<Unit> { cont ->
      val process = try {
        ProcessBuilder(cmd).redirectErrorStream(true).start()
      } catch (t: Throwable) {
        log(TAG, "failed to spawn `dns-sd -R ${info.serviceName} $regtype`: ${t.message}", t)
        cont.cancel(t)
        return@suspendCancellableCoroutine
      }

      log(TAG, "publishing $regtype name=${info.serviceName} port=${info.port} via dns-sd (pid=${process.pid()})")

      // Drain stdout in the background so the subprocess pipe doesn't fill and block. We
      // don't gate completion on a "Name registered and active" line — `dns-sd -R` keeps
      // running for the lifetime of the registration and prints status asynchronously.
      val drainerThread = Thread({
        try {
          BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
            lines.forEach { /* ignore; logging each line would be very noisy */ }
          }
        } catch (_: Throwable) {
          // Stream closed when the process exits; nothing to do.
        }
      }, "dns-sd-R-${info.serviceName}-drain").apply {
        isDaemon = true
        start()
      }

      cont.invokeOnCancellation {
        runCatching { process.destroy() }
        if (!process.waitFor(PROCESS_KILL_TIMEOUT_S, TimeUnit.SECONDS)) {
          runCatching { process.destroyForcibly() }
        }
        runCatching { drainerThread.interrupt() }
      }
    }
  }
}
