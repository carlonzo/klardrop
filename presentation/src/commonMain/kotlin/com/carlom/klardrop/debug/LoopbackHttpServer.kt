package com.carlom.klardrop.debug

import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class HttpRequest(
  val method: String,
  val path: String,
  val query: String,
  val body: String,
)

internal data class HttpResponse(
  val status: Int,
  val body: String,
  val reason: String = if (status == 200) "OK" else "Error",
)

/**
 * Tiny loopback HTTP/1.1 server. One request per connection, JSON in/out.
 * Debug-control only — not a general web server.
 */
internal class LoopbackHttpServer(
  private val host: String,
  private val port: Int,
  dispatcher: CoroutineDispatcher,
  private val handle: suspend (HttpRequest) -> HttpResponse,
) {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private var selector: SelectorManager? = null
  private var server: ServerSocket? = null

  val boundPort: Int
    get() = server?.localAddress?.let { (it as? InetSocketAddress)?.port } ?: port

  suspend fun start() {
    if (server != null) return
    val selectorManager = SelectorManager(scope.coroutineContext)
    selector = selectorManager
    val bound = aSocket(selectorManager).tcp().bind(InetSocketAddress(host, port))
    server = bound
    log("DebugControl", "Listening on $host:${(bound.localAddress as InetSocketAddress).port}")
    scope.launch {
      while (isActive) {
        val socket = try {
          bound.accept()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (isActive) log("DebugControl", "accept failed: ${e.message}")
          break
        }
        launch { handleClient(socket) }
      }
    }
  }

  fun stop() {
    runCatching { server?.close() }
    runCatching { selector?.close() }
    scope.cancel()
    server = null
    selector = null
  }

  private suspend fun handleClient(socket: Socket) {
    try {
      val read = socket.openReadChannel()
      val write = socket.openWriteChannel(autoFlush = true)
      val requestLine = read.readUTF8Line() ?: return
      val parts = requestLine.split(" ")
      if (parts.size < 2) return
      val method = parts[0]
      val target = parts[1]
      val headers = mutableMapOf<String, String>()
      while (true) {
        val line = read.readUTF8Line() ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx > 0) {
          headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
      }
      val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
      val body = if (contentLength > 0) {
        val bytes = ByteArray(contentLength.coerceAtMost(1_000_000))
        read.readFully(bytes)
        bytes.decodeToString()
      } else {
        ""
      }
      val path = target.substringBefore('?')
      val query = target.substringAfter('?', missingDelimiterValue = "")
      val response = try {
        handle(HttpRequest(method, path, query, body))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("DebugControl", "handler error for $method $path: ${e.message}", e)
        HttpResponse(500, jsonError(e.message ?: e::class.simpleName ?: "error"))
      }
      val bodyBytes = response.body.encodeToByteArray()
      val header = "HTTP/1.1 ${response.status} ${response.reason}\r\n" +
        "Content-Type: application/json; charset=utf-8\r\n" +
        "Content-Length: ${bodyBytes.size}\r\n" +
        "Connection: close\r\n\r\n"
      write.writeFully(header.encodeToByteArray())
      write.writeFully(bodyBytes)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("DebugControl", "client I/O failed: ${e.message}")
    } finally {
      runCatching { socket.close() }
    }
  }
}

internal fun jsonError(message: String): String =
  """{"ok":false,"error":${jsonString(message)}}"""

internal fun jsonOk(extra: String = ""): String =
  if (extra.isEmpty()) """{"ok":true}""" else """{"ok":true,$extra}"""

internal fun jsonString(value: String): String = buildString {
  append('"')
  value.forEach { ch ->
    when (ch) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> append(ch)
    }
  }
  append('"')
}
