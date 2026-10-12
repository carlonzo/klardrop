@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.klardrop.common

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import com.carlom.klardrop.common.utils.LogBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryLinuxSenderTest {

  @AfterTest
  fun resetGlobalState() {
    resetLinuxSenderStateForTest()
    CrashReporter.started = false
    CrashReporter.linuxSender = null
    CrashReporter.linuxFeedbackSender = null
    LogBuffer.clear()
  }

  // --- DSN parsing ---

  @Test
  fun parsesDsnWithPort() {
    val dsn = parseDsn("https://key@host.example:9000/42", "1.0")
    assertNotNull(dsn)
    assertEquals("https", dsn.scheme)
    assertEquals("https://key@host.example:9000/42", dsn.originalDsn)
    assertEquals("https://host.example:9000/api/42/envelope/", dsn.envelopeUrl)
    assertTrue(dsn.authHeader.contains("sentry_key=key"))
  }

  @Test
  fun parsesDsnWithoutPort() {
    val dsn = parseDsn("http://key@host.example/42", "1.0")
    assertNotNull(dsn)
    assertEquals("http://host.example/api/42/envelope/", dsn.envelopeUrl)
  }

  @Test
  fun rejectsInvalidDsns() {
    assertNull(parseDsn("not-a-dsn", "1.0"))
    assertNull(parseDsn("https://host.example/42", "1.0")) // no key@
    assertNull(parseDsn("https://key@host.example", "1.0")) // no project path
  }

  // --- Envelope builder ---

  @Test
  fun envelopeHasThreeLinesAndExpectedFields() {
    val dsn = parseDsn("https://key@host.example/42", "1.2.3")!!
    val envelope = buildSentryEnvelope(
      parsedDsn = dsn,
      throwable = IllegalStateException("boom"),
      level = "error",
      appVersion = "1.2.3",
      userId = "device-1",
      userName = "My Laptop",
      osType = "omarchy",
    ).decodeToString()

    val lines = envelope.trimEnd('\n').split("\n")
    assertEquals(3, lines.size)

    val (header, itemHeader, event) = lines
    assertTrue(header.contains("\"dsn\":\"https://key@host.example/42\""))
    val eventIdMatch = Regex("\"event_id\":\"([0-9a-f]{32})\"").find(header)
    assertNotNull(eventIdMatch, "event_id must be 32 lowercase hex chars")

    assertTrue(itemHeader.contains("\"type\":\"event\""))

    assertTrue(event.contains("\"platform\":\"other\""))
    assertTrue(event.contains("\"release\":\"1.2.3\""))
    assertTrue(event.contains("\"environment\":\"production\""))
    assertTrue(event.contains("\"device.platform\":\"linux\""))
    assertTrue(event.contains("\"device.osType\":\"omarchy\""))
    assertTrue(event.contains("\"id\":\"device-1\""))
    assertTrue(event.contains("\"username\":\"My Laptop\""))
    assertTrue(event.contains("\"type\":\"IllegalStateException\""))
    assertTrue(event.contains("\"value\":\"boom\""))
    assertTrue(event.contains("\"log_tail\""))
    assertTrue(event.contains("\"stacktrace\""))
  }

  /**
   * Pins the kotlinx.serialization escaping fix: a hand-rolled escaper previously let
   * control characters below U+0020 (other than \n\r\t) through raw, which Sentry (and
   * any strict JSON parser) rejects. Exception messages and log lines are not our text,
   * so this has to be correct for arbitrary input, not just the happy path.
   */
  @Test
  fun envelopeSurvivesControlCharactersAndParsesAsValidJson() {
    val nasty = "\u001b\u0000\"bad\\input"
    LogBuffer.append(nasty)
    val dsn = parseDsn("https://key@host.example/42", "1.0")!!
    val envelope = buildSentryEnvelope(
      parsedDsn = dsn,
      throwable = IllegalStateException(nasty),
      level = "error",
      appVersion = "1.0",
      userId = nasty,
      userName = "user",
      osType = "omarchy",
    ).decodeToString()

    val lines = envelope.trimEnd('\n').split("\n")
    assertEquals(3, lines.size)
    val parsedLines = lines.map { Json.parseToJsonElement(it) } // throws if any line isn't valid JSON

    val event = parsedLines[2].jsonObject
    val exceptionValue = event["exception"]!!.jsonObject["values"]!!
      .jsonArray[0].jsonObject["value"]!!.jsonPrimitive.content
    assertEquals(nasty, exceptionValue)
    assertEquals(nasty, event["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    assertEquals(nasty, event["extra"]!!.jsonObject["log_tail"]!!.jsonPrimitive.content)
  }

  // --- Feedback envelope builder ---

  @Test
  fun feedbackEnvelopeHasFiveLinesAndExpectedFields() {
    val dsn = parseDsn("https://key@host.example/42", "1.2.3")!!
    val envelope = buildSentryFeedbackEnvelope(
      parsedDsn = dsn,
      comments = "it crashed when I paired",
      name = "Alice",
      email = "alice@example.com",
      appVersion = "1.2.3",
      userId = "device-1",
      userName = "My Laptop",
      osType = "omarchy",
    ).decodeToString()

    val lines = envelope.trimEnd('\n').split("\n")
    assertEquals(5, lines.size)
    val parsedLines = lines.map { Json.parseToJsonElement(it) } // throws if any line isn't valid JSON

    val header = parsedLines[0].jsonObject
    val headerEventId = header["event_id"]!!.jsonPrimitive.content

    val eventItemHeader = parsedLines[1].jsonObject
    assertEquals("event", eventItemHeader["type"]!!.jsonPrimitive.content)

    val event = parsedLines[2].jsonObject
    assertEquals("info", event["level"]!!.jsonPrimitive.content)
    assertEquals("user", event["tags"]!!.jsonObject["report"]!!.jsonPrimitive.content)
    assertEquals(headerEventId, event["event_id"]!!.jsonPrimitive.content)

    val userReportItemHeader = parsedLines[3].jsonObject
    assertEquals("user_report", userReportItemHeader["type"]!!.jsonPrimitive.content)

    val userReport = parsedLines[4].jsonObject
    assertEquals(headerEventId, userReport["event_id"]!!.jsonPrimitive.content)
    assertTrue(userReport["comments"]!!.jsonPrimitive.content.contains("it crashed"))
    assertEquals("alice@example.com", userReport["email"]!!.jsonPrimitive.content)
  }

  // --- Cap / dedupe (pure, no curl spawned; noise filtering happens in CrashReporter.notify) ---

  @Test
  fun duplicateEventIsSuppressed() {
    assertTrue(shouldSendEvent("IllegalStateException", "boom"))
    assertFalse(shouldSendEvent("IllegalStateException", "boom"))
  }

  @Test
  fun eventCapStopsAfterTwentyDistinctEvents() {
    repeat(20) { i -> assertTrue(shouldSendEvent("IllegalStateException", "boom-$i")) }
    assertFalse(shouldSendEvent("IllegalStateException", "boom-21st"))
  }

  // --- Headless guard: init + every reporting method must not crash on linuxX64 ---

  @Test
  fun productionInitWithLinuxSenderNeverCrashes() {
    // Port 1 is reliably closed on loopback, so curl fails fast (connection refused)
    // instead of waiting out --max-time.
    initCrashReporter("1.0", isProduction = true, dsn = "http://testkey@127.0.0.1:1/1")
    CrashReporter.notify(IllegalStateException("boom"))
    CrashReporter.leaveBreadcrumb("hello")
    CrashReporter.setUser("device-1", "My Laptop", "omarchy")
    // Port 1 is closed, so the real curl-based feedback sender fails fast rather than
    // being Disabled (a feedback sender now exists whenever the linux crash sender does).
    assertEquals(ReportOutcome.Failed, CrashReporter.reportUserFeedback("test"))
  }

  @Test
  fun productionFalseNeverStarts() {
    initCrashReporter("1.0", isProduction = false, dsn = "http://testkey@127.0.0.1:1/1")
    assertFalse(CrashReporter.started)
    assertNull(CrashReporter.linuxSender)
  }

  @Test
  fun injectedFakeSenderCapturesExactlyOneEvent() {
    val captured = mutableListOf<Throwable>()
    CrashReporter.linuxSender = { throwable, _, _, _, _ -> captured.add(throwable) }
    CrashReporter.started = true

    CrashReporter.notify(IllegalStateException("boom"))

    assertEquals(1, captured.size)
  }

  /** notify() itself filters expected protocol noise before ever reaching the sender. */
  @Test
  fun notifyNeverInvokesSenderForKnownNoise() {
    val captured = mutableListOf<Throwable>()
    CrashReporter.linuxSender = { throwable, _, _, _, _ -> captured.add(throwable) }
    CrashReporter.started = true

    CrashReporter.notify(IllegalStateException("disconnected during handshake: peer went away"))

    assertTrue(captured.isEmpty())
  }

  @Test
  fun notifyFatalUsesFatalLevel() {
    val capturedLevels = mutableListOf<String>()
    CrashReporter.linuxSender = { _, level, _, _, _ -> capturedLevels.add(level) }
    CrashReporter.started = true

    CrashReporter.notify(IllegalStateException("x"), fatal = true)
    CrashReporter.notify(IllegalStateException("y"))

    assertEquals(listOf("fatal", "error"), capturedLevels)

    capturedLevels.clear()
    com.carlom.klardrop.common.utils.reportUncaughtException("T", IllegalStateException("z"), fatal = true)
    assertEquals(listOf("fatal"), capturedLevels)
  }

  @Test
  fun setUserPassesIdentityThroughToSender() {
    val captured = mutableListOf<String>()
    CrashReporter.linuxSender = { _, _, userId, userName, osType ->
      captured.add("$userId/$userName/$osType")
    }
    CrashReporter.started = true

    CrashReporter.setUser("device-1", "My Laptop", "omarchy")
    CrashReporter.notify(IllegalStateException("boom"))

    assertEquals(listOf("device-1/My Laptop/omarchy"), captured)
  }

  // --- One real curl round-trip against a loopback listener ---

  @Test
  fun realCurlRoundTripHitsLoopbackListener() = memScoped {
    val serverFd = socket(AF_INET, SOCK_STREAM, 0)
    assertTrue(serverFd >= 0)
    val addr = alloc<sockaddr_in>().apply {
      sin_family = AF_INET.toUShort()
      sin_port = 0u // ephemeral
      sin_addr.s_addr = htonl(0x7F000001u)
    }
    assertEquals(0, bind(serverFd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().toUInt()))
    assertEquals(0, listen(serverFd, 1))

    val boundAddr = alloc<sockaddr_in>()
    val boundLen = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().toUInt() }
    getsockname(serverFd, boundAddr.ptr.reinterpret<sockaddr>(), boundLen.ptr)
    val port = ntohs(boundAddr.sin_port).toInt()

    val sender = platformCrashSender("http://testkey@127.0.0.1:$port/42", "1.0")!!

    // curl (via execProcess) blocks until it gets a response, so it has to run on a
    // separate thread while this one accept()s and reads the request.
    var request = ""
    runBlocking {
      val curlJob = async(Dispatchers.Default) {
        sender(IllegalStateException("round trip"), "error", "device-1", "My Laptop", "omarchy")
      }
      val clientFd = accept(serverFd, null, null)
      assertTrue(clientFd >= 0)
      val buf = allocArray<ByteVar>(4096)
      val n = read(clientFd, buf, 4095UL)
      if (n > 0) request = buf.readBytes(n.toInt()).decodeToString()
      close(clientFd)
      curlJob.await()
    }
    close(serverFd)

    val requestLine = request.lineSequence().firstOrNull().orEmpty()
    assertEquals("POST /api/42/envelope/ HTTP/1.1", requestLine.trimEnd('\r'))
    assertTrue(request.contains("sentry_key=testkey"), "expected sentry_key in request:\n$request")
  }
}
