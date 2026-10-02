package com.carlom.klardrop.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LoopbackHttpServerTest {

  @Test
  fun getAndPostRoundTrip() = runBlocking {
    val server = LoopbackHttpServer(
      host = "127.0.0.1",
      port = 0,
      dispatcher = Dispatchers.IO,
    ) { request ->
      when {
        request.method == "GET" && request.path == "/health" ->
          HttpResponse(200, """{"ok":true,"path":"${request.path}"}""")
        request.method == "POST" && request.path == "/echo" ->
          HttpResponse(200, """{"ok":true,"body":${jsonString(request.body)}}""")
        else -> HttpResponse(404, jsonError("unknown"))
      }
    }
    server.start()
    try {
      val port = server.boundPort
      assertTrue(port > 0, "ephemeral bind must report the live port")

      val health = URI("http://127.0.0.1:$port/health").toURL().readText()
      assertTrue(health.contains("\"ok\":true"), health)

      val conn = URI("http://127.0.0.1:$port/echo").toURL().openConnection() as HttpURLConnection
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      val payload = """{"deviceId":"abc"}"""
      conn.outputStream.use { it.write(payload.encodeToByteArray()) }
      val echo = conn.inputStream.bufferedReader().readText()
      assertTrue(echo.contains("abc"), echo)
      assertEquals(200, conn.responseCode)
    } finally {
      server.stop()
    }
  }

  @Test
  fun tokenRejectionAndAcceptance() = runBlocking {
    val testToken = "test-token-xyz-123"
    val server = LoopbackHttpServer(
      host = "127.0.0.1",
      port = 0,
      authToken = testToken,
      dispatcher = Dispatchers.IO,
    ) { request ->
      if (request.method == "GET" && request.path == "/health") {
        HttpResponse(200, """{"ok":true}""")
      } else {
        HttpResponse(404, jsonError("not found"))
      }
    }
    server.start()
    try {
      val port = server.boundPort
      assertTrue(port > 0)

      // 1. Missing token -> 401
      val unauthConn = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
      unauthConn.requestMethod = "GET"
      assertEquals(401, unauthConn.responseCode)
      val unauthBody = unauthConn.errorStream.bufferedReader().readText()
      assertTrue(unauthBody.contains("unauthorized"), unauthBody)

      // 2. Wrong token -> 401
      val wrongConn = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
      wrongConn.requestMethod = "GET"
      wrongConn.setRequestProperty("Authorization", "Bearer wrong-token")
      assertEquals(401, wrongConn.responseCode)

      // 3. Valid token -> 200
      val validConn = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
      validConn.requestMethod = "GET"
      validConn.setRequestProperty("Authorization", "Bearer $testToken")
      assertEquals(200, validConn.responseCode)
      val validBody = validConn.inputStream.bufferedReader().readText()
      assertTrue(validBody.contains("\"ok\":true"), validBody)
    } finally {
      server.stop()
    }
  }

  @Test
  fun stateLongPollWaitsForChange() = runBlocking {
    val versionFlow = MutableStateFlow(1L)
    val server = LoopbackHttpServer(
      host = "127.0.0.1",
      port = 0,
      dispatcher = Dispatchers.IO,
    ) { request ->
      if (request.method == "GET" && request.path == "/state") {
        val since = request.query.substringAfter("since=", "").substringBefore("&").toLongOrNull()
        if (since != null) {
          withTimeoutOrNull(5000L) {
            versionFlow.first { it > since }
          }
        }
        HttpResponse(200, """{"ok":true,"version":${versionFlow.value}}""")
      } else {
        HttpResponse(404, jsonError("not found"))
      }
    }
    server.start()
    try {
      val port = server.boundPort

      // 1. Immediate response when since=0 (current is 1)
      val initialConn = URI("http://127.0.0.1:$port/state?since=0").toURL().openConnection() as HttpURLConnection
      assertEquals(200, initialConn.responseCode)
      val initialBody = initialConn.inputStream.bufferedReader().readText()
      assertTrue(initialBody.contains("\"version\":1"), initialBody)

      // 2. Long-poll waits when since=1 until version increments to 2
      val pollJob = async(Dispatchers.IO) {
        val conn = URI("http://127.0.0.1:$port/state?since=1").toURL().openConnection() as HttpURLConnection
        conn.readTimeout = 5000
        val code = conn.responseCode
        val text = conn.inputStream.bufferedReader().readText()
        code to text
      }

      // Allow request to arrive and start waiting
      delay(100)
      assertFalse(pollJob.isCompleted, "Long-poll must be suspended waiting for version > 1")

      // Trigger change
      versionFlow.update { 2L }

      val (code, text) = pollJob.await()
      assertEquals(200, code)
      assertTrue(text.contains("\"version\":2"), "Expected version 2 after change, got: $text")
    } finally {
      server.stop()
    }
  }

  @Test
  fun controlFilePersistenceAndPermissions() {
    val port = 9876
    val token = "perm-test-token"
    writeControlFile(port, token, forBuild(isDebug = true))
    val pathStr = resolveControlFilePath()
    assertTrue(pathStr != null, "Path must be resolvable")
    val file = File(pathStr)
    assertTrue(file.exists(), "Control file must exist")
    val content = file.readText()
    assertTrue(content.contains("\"port\":9876"), content)
    assertTrue(content.contains("\"token\":\"perm-test-token\""), content)
    assertTrue(content.contains("\"apiVersion\":1"), content)
    assertTrue(content.contains("\"logs\""), content)

    // Check POSIX permissions on supported systems (Linux/Mac)
    try {
      val perms = Files.getPosixFilePermissions(file.toPath())
      val permStr = PosixFilePermissions.toString(perms)
      assertEquals("rw-------", permStr, "Expected 0600 mode")
    } catch (_: UnsupportedOperationException) {
      // Not POSIX file system (e.g. Windows)
    }

    // Deleting with mismatched token must NOT delete the file
    deleteControlFile("different-token")
    assertTrue(file.exists(), "Control file must not be deleted if token differs")

    // Deleting with matching token must delete the file
    deleteControlFile(token)
    assertFalse(file.exists(), "Control file must be deleted when token matches")
  }

  @Test
  fun controlFileJsonMatchesTheDocumentedShape() {
    assertEquals(
      """{"port":4321,"token":"abc-123","apiVersion":1,"capabilities":["state","capabilities"]}""",
      controlFileJson(4321, "abc-123", listOf("state", "capabilities")),
    )
  }

  @Test
  fun stateExposesTransfersAndLongPollWakesOnProgress() = runBlocking {
    val versionFlow = MutableStateFlow(1L)
    var activeTransfer = true
    var transferredSize = 0L

    val server = LoopbackHttpServer(
      host = "127.0.0.1",
      port = 0,
      dispatcher = Dispatchers.IO,
    ) { request ->
      if (request.method == "GET" && request.path == "/state") {
        val since = request.query.substringAfter("since=", "").substringBefore("&").toLongOrNull()
        if (since != null) {
          withTimeoutOrNull(5000L) {
            versionFlow.first { it > since }
          }
        }
        val payload = kotlinx.serialization.json.buildJsonObject {
          put("ok", true)
          put("version", versionFlow.value)
          put("transfers", kotlinx.serialization.json.buildJsonArray {
            if (activeTransfer) {
              add(
                kotlinx.serialization.json.buildJsonObject {
                  put("id", "rx-1")
                  put("deviceId", "dev-abc")
                  put("fileName", "photo.jpg")
                  put("totalSize", 1000L)
                  put("transferredSize", transferredSize)
                  put("isSender", false)
                }
              )
            }
          })
        }.toString()
        HttpResponse(200, payload)
      } else {
        HttpResponse(404, jsonError("not found"))
      }
    }
    server.start()
    try {
      val port = server.boundPort

      // 1. Initial state contains transfers array with in-progress transfer
      val initialConn = URI("http://127.0.0.1:$port/state?since=0").toURL().openConnection() as HttpURLConnection
      assertEquals(200, initialConn.responseCode)
      val initialBody = initialConn.inputStream.bufferedReader().readText()
      assertTrue(initialBody.contains("\"transfers\":["), initialBody)
      assertTrue(initialBody.contains("\"fileName\":\"photo.jpg\""), initialBody)
      assertTrue(initialBody.contains("\"transferredSize\":0"), initialBody)
      assertFalse(initialBody.contains("\"status\""), "status field should not be present")

      // 2. Long-poll waiting for progress update
      val pollJob = async(Dispatchers.IO) {
        val conn = URI("http://127.0.0.1:$port/state?since=1").toURL().openConnection() as HttpURLConnection
        conn.readTimeout = 5000
        val code = conn.responseCode
        val text = conn.inputStream.bufferedReader().readText()
        code to text
      }

      delay(100)
      assertFalse(pollJob.isCompleted, "Long-poll must wait for version bump")

      // Progress update occurs
      transferredSize = 500L
      versionFlow.update { 2L }

      val (code, text) = pollJob.await()
      assertEquals(200, code)
      assertTrue(text.contains("\"version\":2"), text)
      assertTrue(text.contains("\"transferredSize\":500"), text)

      // 3. Completion evicts transfer from active list and bumps version
      val completionPollJob = async(Dispatchers.IO) {
        val conn = URI("http://127.0.0.1:$port/state?since=2").toURL().openConnection() as HttpURLConnection
        conn.readTimeout = 5000
        val compCode = conn.responseCode
        val compText = conn.inputStream.bufferedReader().readText()
        compCode to compText
      }

      delay(100)
      activeTransfer = false
      versionFlow.update { 3L }

      val (compCode, compText) = completionPollJob.await()
      assertEquals(200, compCode)
      assertTrue(compText.contains("\"version\":3"), compText)
      assertTrue(compText.contains("\"transfers\":[]"), compText)
    } finally {
      server.stop()
    }
  }

  @Test
  fun startOnBusyPortThrowsControlPortInUse() = runBlocking {
    val occupied = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    try {
      val server = LoopbackHttpServer(
        host = "127.0.0.1",
        port = occupied.localPort,
        dispatcher = Dispatchers.IO,
      ) { HttpResponse(200, jsonOk()) }

      val exception = assertFailsWith<ControlPortInUseException> { server.start() }
      assertEquals(occupied.localPort, exception.port)
    } finally {
      occupied.close()
    }
  }
}
