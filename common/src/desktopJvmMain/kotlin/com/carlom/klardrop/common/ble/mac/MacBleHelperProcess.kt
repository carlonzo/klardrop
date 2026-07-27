package com.carlom.klardrop.common.ble.mac

import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Manages the long-lived `klardrop-ble-helper` Swift process and exposes a
 * Kotlin-native facade over its NDJSON protocol. One instance per
 * `BleTransport.desktopJvm`.
 *
 * Lifecycle:
 *  - The helper is spawned lazily on first call to [ensureStarted].
 *  - On unexpected exit the process is restarted with exponential backoff (1s → 30s).
 *    Each crash is reported through `log(tag, msg, throwable)` so Bugsnag captures it.
 *  - If the helper crashes more than [maxCrashesPerWindow] times within
 *    [crashWindow], the helper is parked and BLE reports unsupported for the rest of
 *    the session. Logs continue to flow on every crash.
 *  - Active state (advertise on/off, scan on/off) is replayed automatically after
 *    a successful restart.
 *
 * Tests inject a fake binary via [commandProvider]; production code uses
 * [HelperBinaryResolver] to extract the bundled binary.
 */
internal class MacBleHelperProcess(
  private val commandProvider: () -> List<String>?,
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  private val maxCrashesPerWindow: Int = 5,
  private val crashWindow: Duration = 60.seconds,
  private val responseTimeout: Duration = 10.seconds,
) {

  private val startMutex = Mutex()
  private val writeMutex = Mutex()

  // Active process; null when not yet spawned, or after a permanent shutdown.
  @Volatile private var process: Process? = null
  @Volatile private var stdinWriter: PrintWriter? = null

  // Most recent CB state we got from the helper. `unknown` until the first state event.
  private val _state = MutableStateFlow(HelperState.UNKNOWN)
  val state: kotlinx.coroutines.flow.StateFlow<HelperState> = _state.asStateFlow()

  // Pending requests awaiting a response from the helper, keyed by request id.
  private val pending = ConcurrentHashMap<String, CompletableDeferred<HelperLine>>()

  // Active sessions keyed by helper-assigned sessionId.
  private val sessions = ConcurrentHashMap<String, MacBleHelperSession>()

  // Inbound peripheral-role sessions feed BleServerListener via [serveGatt].
  private val inboundSessions = Channel<MacBleHelperSession>(capacity = Channel.UNLIMITED)

  // Scan events fan out via SharedFlow; collectors share the helper's single scanner.
  private val peerEvents = MutableSharedFlow<BlePeerEvent>(extraBufferCapacity = 64)
  private val scanCollectors = MutableStateFlow(0)

  // Replay state across restarts.
  @Volatile private var lastAdvertiseShortDeviceId: String? = null
  @Volatile private var lastAdvertiseLocalName: String? = null
  @Volatile private var scanActive = false

  private val crashStamps = ArrayDeque<TimeSource.Monotonic.ValueTimeMark>()
  @Volatile private var permanentlyDisabled = false

  private var monitorJob: Job? = null

  // Background task to drain stderr and forward to log, started per-process.
  private var stderrJob: Job? = null
  private var stdoutJob: Job? = null

  /**
   * Spawn the helper if it isn't running and wait until we have a usable state.
   * Returns true if the helper is alive and we have at least one state report.
   */
  suspend fun ensureStarted(): Boolean {
    if (permanentlyDisabled) return false
    if (process?.isAlive == true) return true

    return startMutex.withLock {
      if (process?.isAlive == true) return@withLock true
      if (permanentlyDisabled) return@withLock false
      val cmd = commandProvider() ?: run {
        log(TAG, "BLE helper binary not available on this host; BLE disabled")
        return@withLock false
      }
      try {
        spawn(cmd)
      } catch (t: Throwable) {
        log(TAG, "Failed to spawn BLE helper", t)
        return@withLock false
      }
      // Reply to init is immediate; the state event arrives asynchronously after
      // CBCentralManager finishes its first state-update callback. Wait for both so
      // callers can rely on isPoweredOn() right after ensureStarted().
      try {
        withTimeout(responseTimeout) { sendCommand(HelperCommands.INIT) }
      } catch (t: Throwable) {
        log(TAG, "BLE helper init handshake failed", t)
      }
      try {
        withTimeout(responseTimeout) {
          _state.first { it != HelperState.UNKNOWN }
        }
      } catch (t: Throwable) {
        log(TAG, "BLE helper did not report state within timeout (state=${_state.value})")
      }
      true
    }
  }

  /** Suspending variant of [isPoweredOn] that waits for the first state report. */
  suspend fun awaitPoweredOn(): Boolean {
    if (!ensureStarted()) return false
    return _state.value == HelperState.POWERED_ON
  }

  fun isPoweredOn(): Boolean = _state.value == HelperState.POWERED_ON

  /**
   * Start advertising. [localName] is the AD local-name record chosen by
   * `klardropAdvertisePayload` — the only channel CoreBluetooth lets a peripheral use to
   * carry the id, since it drops custom service-data. The helper broadcasts it verbatim;
   * [shortDeviceId] is kept for replay bookkeeping across helper restarts.
   */
  suspend fun startAdvertising(shortDeviceId: String, localName: String?) {
    if (!ensureStarted()) return
    lastAdvertiseShortDeviceId = shortDeviceId
    lastAdvertiseLocalName = localName
    sendCommand(
      HelperCommands.ADVERTISE_START,
      mapOf(
        "shortDeviceId" to stringField(shortDeviceId),
      ) + (localName?.let { mapOf("localName" to stringField(it)) } ?: emptyMap())
    )
  }

  suspend fun stopAdvertising() {
    if (!ensureStarted()) return
    lastAdvertiseShortDeviceId = null
    lastAdvertiseLocalName = null
    runCatching { sendCommand(HelperCommands.ADVERTISE_STOP) }
  }

  /**
   * Cold flow that returns peer events from the helper's scanner. The scanner is
   * started when the first collector subscribes and stopped when the last one
   * cancels — multiple collectors share a single radio scan.
   */
  fun scanForPeers(): Flow<BlePeerEvent> = callbackFlow {
    val started = ensureStarted()
    if (!started) {
      close()
      return@callbackFlow
    }
    val previousCollectors = scanCollectors.value
    scanCollectors.value = previousCollectors + 1
    if (previousCollectors == 0) {
      runCatching { sendCommand(HelperCommands.SCAN_START) }
        .onSuccess { scanActive = true }
        .onFailure { log(TAG, "scan_start failed: ${it.message}") }
    }
    val job = scope.launch {
      peerEvents.collect { event -> trySend(event) }
    }
    awaitClose {
      job.cancel()
      val remaining = scanCollectors.value - 1
      scanCollectors.value = remaining.coerceAtLeast(0)
      if (remaining <= 0 && process?.isAlive == true) {
        scope.launch {
          runCatching { sendCommand(HelperCommands.SCAN_STOP) }
            .onSuccess { scanActive = false }
        }
      }
    }
  }

  /**
   * Connect as central to a peer that was previously surfaced by [scanForPeers].
   * The returned session is alive once notifications are subscribed.
   */
  suspend fun connectCentral(peerId: String, remoteShortDeviceId: String): MacBleHelperSession {
    check(ensureStarted()) { "BLE helper not available" }
    val response = sendCommand(
      HelperCommands.CONNECT,
      mapOf("peerId" to stringField(peerId)),
    )
    val sessionId = response.obj.string("sessionId")
      ?: throw IllegalStateException("Helper connect response missing sessionId")
    val mtu = response.obj.int("mtu")
      ?: throw IllegalStateException("Helper connect response missing mtu")
    val session = MacBleHelperSession(
      sessionId = sessionId,
      deviceId = remoteShortDeviceId,
      mtu = mtu,
      helper = this,
    )
    sessions[sessionId] = session
    return session
  }

  /** Inbound peripheral-role sessions opened by remote centrals subscribing to RX. */
  fun serveGatt(): Flow<MacBleHelperSession> = callbackFlow {
    val started = ensureStarted()
    if (!started) {
      close()
      return@callbackFlow
    }
    // Drain any sessions that arrived before this collector showed up.
    val drainerJob = scope.launch {
      for (session in inboundSessions) {
        if (!trySend(session).isSuccess) break
      }
    }
    awaitClose { drainerJob.cancel() }
  }

  /** Send a chunk on the given session; suspends until the helper acknowledges the GATT write. */
  internal suspend fun sendChunk(sessionId: String, bytes: ByteArray) {
    check(process?.isAlive == true) { "BLE helper process is not running" }
    sendCommand(
      HelperCommands.SEND_CHUNK,
      mapOf(
        "sessionId" to stringField(sessionId),
        "data" to stringField(Base64.getEncoder().encodeToString(bytes)),
      )
    )
  }

  /** Fire-and-forget close request; safe to call after the session is already gone. */
  internal fun scheduleCloseSession(sessionId: String) {
    val proc = process ?: return
    if (!proc.isAlive) return
    scope.launch {
      runCatching {
        sendCommand(HelperCommands.CLOSE_SESSION, mapOf("sessionId" to stringField(sessionId)))
      }
    }
  }

  /** Stop the helper for good. Subsequent calls will not respawn it. */
  fun shutdown() {
    permanentlyDisabled = true
    monitorJob?.cancel()
    stdoutJob?.cancel()
    stderrJob?.cancel()
    val proc = process
    process = null
    stdinWriter = null
    if (proc != null && proc.isAlive) {
      runCatching {
        // Best-effort polite shutdown.
        val w = PrintWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8), true)
        w.println(HelperProtocol.encodeRequest(UUID.randomUUID().toString(), HelperCommands.SHUTDOWN))
      }
      runCatching { proc.destroy() }
    }
    sessions.values.forEach { it.markRemoteClosed() }
    sessions.clear()
    pending.values.forEach { it.completeExceptionally(IllegalStateException("Helper shut down")) }
    pending.clear()
    inboundSessions.close()
  }

  // ──────────────────────────────────────────────────────────────────────────────────
  // Internals.
  // ──────────────────────────────────────────────────────────────────────────────────

  private suspend fun spawn(command: List<String>) {
    val proc = ProcessBuilder(command)
      .redirectErrorStream(false)
      .start()
    process = proc
    stdinWriter = PrintWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8), /* autoFlush = */ true)

    stdoutJob = scope.launch { drainStdout(proc) }
    stderrJob = scope.launch { drainStderr(proc) }
    monitorJob = scope.launch { monitorExit(proc, command) }

    // Replay state across restart.
    lastAdvertiseShortDeviceId?.let { id ->
      scope.launch {
        runCatching {
          sendCommand(
            HelperCommands.ADVERTISE_START,
            mapOf("shortDeviceId" to stringField(id)) +
              (lastAdvertiseLocalName?.let { mapOf("localName" to stringField(it)) } ?: emptyMap())
          )
        }
      }
    }
    if (scanActive) {
      scope.launch { runCatching { sendCommand(HelperCommands.SCAN_START) } }
    }
  }

  private suspend fun drainStdout(proc: Process) {
    val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
    try {
      while (true) {
        val line = withContextOrNull { reader.readLine() } ?: break
        if (line.isEmpty()) continue
        handleLine(line)
      }
    } catch (e: IOException) {
      // Process exited; monitor handles restart.
    }
  }

  private suspend fun drainStderr(proc: Process) {
    val reader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
    try {
      while (true) {
        val line = withContextOrNull { reader.readLine() } ?: break
        if (line.isNotBlank()) log(TAG, "helper stderr: $line")
      }
    } catch (_: IOException) {
      // ignore — process exit
    }
  }

  private suspend fun monitorExit(proc: Process, command: List<String>) {
    val exit = withContextOrNull { proc.waitFor() } ?: return
    if (process !== proc) return // already replaced
    process = null
    stdinWriter = null
    sessions.values.forEach { it.markRemoteClosed() }
    sessions.clear()
    pending.values.forEach { it.completeExceptionally(IllegalStateException("Helper exited (code=$exit) before response")) }
    pending.clear()

    if (permanentlyDisabled) return

    // Exit 134 = SIGABRT. The dominant cause is macOS TCC killing the helper
    // because the responsible app (often the launcher of the JVM, e.g. a
    // terminal or IDE) lacks NSBluetoothAlwaysUsageDescription. The packaged
    // .app bundle declares it; running outside that bundle (gradle :desktop:run)
    // can't be fixed from here — Apple removed the disclaim SPI escape hatch.
    // Treat it as a permanent denial so we don't burn 5 retries logging the
    // same crash.
    if (exit == TCC_ABORT_EXIT_CODE) {
      permanentlyDisabled = true
      log(
        TAG,
        "BLE helper killed by macOS (exit=134, SIGABRT). This usually means the " +
          "process that launched this app lacks Bluetooth permission. BLE will be " +
          "disabled for this session. To enable BLE, run the packaged .app from " +
          "the DMG (which declares NSBluetoothAlwaysUsageDescription) and grant " +
          "Bluetooth in System Settings → Privacy & Security → Bluetooth."
      )
      return
    }

    val now = TimeSource.Monotonic.markNow()
    crashStamps.addLast(now)
    while (crashStamps.isNotEmpty() && crashStamps.first().elapsedNow() > crashWindow) {
      crashStamps.removeFirst()
    }
    log(
      TAG,
      "helper exited unexpectedly (exit=$exit, recent_crashes=${crashStamps.size})",
      IllegalStateException("klardrop-ble-helper exited with code $exit"),
    )
    if (crashStamps.size >= maxCrashesPerWindow) {
      permanentlyDisabled = true
      log(TAG, "BLE helper crash budget exhausted; disabling for the rest of this session")
      return
    }
    val backoff = backoffForAttempt(crashStamps.size)
    delay(backoff)
    runCatching { spawn(command) }
      .onFailure { log(TAG, "Restart failed", it) }
  }

  private fun backoffForAttempt(crashes: Int): Duration {
    // 1s, 2s, 4s, 8s, 16s, capped at 30s.
    val seconds = (1L shl (crashes - 1).coerceAtLeast(0)).coerceAtMost(30L)
    return seconds.seconds
  }

  // CompletableDeferred response correlation. Caller suspends on awaitOrThrow.
  private suspend fun sendCommand(cmd: String, fields: Map<String, JsonElement> = emptyMap()): HelperLine.Ok {
    val id = UUID.randomUUID().toString()
    val line = HelperProtocol.encodeRequest(id, cmd, fields)
    val deferred = CompletableDeferred<HelperLine>()
    pending[id] = deferred
    try {
      writeLine(line)
    } catch (t: Throwable) {
      pending.remove(id)
      throw t
    }
    val response = try {
      withTimeout(responseTimeout) { deferred.await() }
    } catch (e: TimeoutCancellationException) {
      pending.remove(id)
      throw IllegalStateException("Helper command '$cmd' timed out after $responseTimeout", e)
    }
    return when (response) {
      is HelperLine.Ok -> response
      is HelperLine.Error -> throw IllegalStateException("Helper command '$cmd' failed: ${response.code} ${response.message}")
      is HelperLine.Event -> throw IllegalStateException("Unexpected event in response slot for '$cmd'")
    }
  }

  private suspend fun writeLine(line: String) {
    val writer = stdinWriter ?: throw IllegalStateException("Helper not started")
    writeMutex.withLock {
      writer.println(line)
      if (writer.checkError()) throw IOException("Failed to write to helper stdin")
    }
  }

  private fun handleLine(line: String) {
    val parsed = HelperProtocol.parseLine(line)
    if (parsed == null) {
      log(TAG, "Could not parse helper line: $line")
      return
    }
    when (parsed) {
      is HelperLine.Ok, is HelperLine.Error -> {
        val id = (parsed as? HelperLine.Ok)?.id ?: (parsed as HelperLine.Error).id
        pending.remove(id)?.complete(parsed)
      }
      is HelperLine.Event -> handleEvent(parsed)
    }
  }

  private fun handleEvent(event: HelperLine.Event) {
    when (event.name) {
      HelperEvents.STATE -> {
        val newState = HelperState.fromString(event.obj.string("state"))
        _state.value = newState
        log(TAG, "helper state: $newState")
      }
      HelperEvents.PEER_FOUND -> {
        decodePeerFound(event.obj)?.let { peerEvents.tryEmit(it) }
      }
      HelperEvents.PEER_LOST -> {
        val address = event.obj.string("peerId") ?: return
        peerEvents.tryEmit(BlePeerEvent.Lost(address))
      }
      HelperEvents.SESSION_OPENED -> {
        val sessionId = event.obj.string("sessionId") ?: return
        val mtu = event.obj.int("mtu") ?: return
        val role = event.obj.string("role") ?: "peripheral"
        val peerShort = event.obj.string("peerShortDeviceId") ?: sessionId
        if (role == "peripheral") {
          val session = MacBleHelperSession(
            sessionId = sessionId,
            deviceId = peerShort,
            mtu = mtu,
            helper = this,
          )
          sessions[sessionId] = session
          inboundSessions.trySend(session)
        }
        // Central-role sessions are minted in connectCentral() against the response;
        // we do not also create one here.
      }
      HelperEvents.CHUNK -> {
        val sessionId = event.obj.string("sessionId") ?: return
        val b64 = event.obj.string("data") ?: return
        val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return
        sessions[sessionId]?.pushChunk(bytes)
      }
      HelperEvents.SESSION_CLOSED -> {
        val sessionId = event.obj.string("sessionId") ?: return
        sessions.remove(sessionId)?.markRemoteClosed()
      }
      HelperEvents.LOG -> {
        val level = event.obj.string("level") ?: "info"
        val message = event.obj.string("message") ?: ""
        if (level == "error") {
          log(TAG, "helper log: $message", IllegalStateException("helper-reported error"))
        } else {
          log(TAG, "helper $level: $message")
        }
      }
    }
  }

  /**
   * Simple wrapper that swallows InterruptedException so blocking IO in coroutines
   * exits cleanly when the scope is cancelled. We avoid Dispatchers.IO context
   * shifting since the caller already runs on it.
   */
  private suspend fun <T> withContextOrNull(block: () -> T): T? = try {
    block()
  } catch (_: InterruptedException) {
    null
  } catch (_: IOException) {
    null
  }

  internal companion object {
    const val TAG = "MacBleHelper"
    private const val TCC_ABORT_EXIT_CODE = 134

    /** State reported by `CBManager.state` on the helper side. */
    enum class HelperState {
      UNKNOWN, POWERED_OFF, POWERED_ON, RESETTING, UNAUTHORIZED, UNSUPPORTED;

      companion object {
        fun fromString(s: String?): HelperState = when (s) {
          "poweredOn" -> POWERED_ON
          "poweredOff" -> POWERED_OFF
          "resetting" -> RESETTING
          "unauthorized" -> UNAUTHORIZED
          "unsupported" -> UNSUPPORTED
          else -> UNKNOWN
        }
      }
    }
  }
}
