package com.carlom.klardrop.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

      val health = URL("http://127.0.0.1:$port/health").readText()
      assertTrue(health.contains("\"ok\":true"), health)

      val conn = URL("http://127.0.0.1:$port/echo").openConnection() as HttpURLConnection
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
}
