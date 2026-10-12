package com.carlom.klardrop.control

import com.carlom.klardrop.common.utils.log
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Thrown by [LoopbackHttpServer.start] when [port] is already bound by another process. */
class ControlPortInUseException(val port: Int, cause: Throwable) :
  Exception("control port $port already in use", cause)

private fun Throwable.looksLikeAddressInUse(): Boolean {
  val text = message ?: return false
  return text.contains("EADDRINUSE", ignoreCase = true) ||
    text.contains("Address already in use", ignoreCase = true)
}

internal data class HttpRequest(
  val method: String,
  val path: String,
  val query: String,
  val body: String,
  val headers: Map<String, String> = emptyMap(),
)

internal data class HttpResponse(
  val status: Int,
  val body: String,
  val reason: String = defaultReason(status),
)

private fun defaultReason(status: Int): String = when (status) {
  200 -> "OK"
  400 -> "Bad Request"
  401 -> "Unauthorized"
  403 -> "Forbidden"
  404 -> "Not Found"
  409 -> "Conflict"
  413 -> "Payload Too Large"
  431 -> "Request Header Fields Too Large"
  500 -> "Internal Server Error"
  503 -> "Service Unavailable"
  else -> if (status in 200..299) "OK" else "Error"
}

/** A malformed/oversized request the server rejects before it ever reaches [handle]. */
private class HttpProtocolError(val status: Int, message: String) : Exception(message)

/**
 * Tiny loopback HTTP/1.1 server. One request per connection, JSON in/out.
 * Loopback control plane only — not a general web server.
 */
internal class LoopbackHttpServer(
  private val host: String,
  private val port: Int,
  private val authToken: String? = null,
  dispatcher: CoroutineDispatcher,
  /** Whole request (headers + body) must be read within this long, or the connection is dropped. */
  private val requestTimeoutMs: Long = 10_000L,
  private val handle: suspend (HttpRequest) -> HttpResponse,
) {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private var selector: SelectorManager? = null
  private var server: ServerSocket? = null

  private companion object {
    /** Body byte cap. A client announcing more via Content-Length is rejected with 413 before
     *  the server reads a single body byte. */
    const val MAX_BODY_BYTES = 1_000_000
    const val MAX_HEADER_COUNT = 100
    const val MAX_HEADER_LINE_BYTES = 8192
  }

  val boundPort: Int
    get() = server?.localAddress?.let { (it as? InetSocketAddress)?.port } ?: port

  suspend fun start() {
    if (server != null) return
    val selectorManager = SelectorManager(scope.coroutineContext)
    selector = selectorManager
    val bound = try {
      aSocket(selectorManager).tcp().bind(InetSocketAddress(host, port)) {
        reuseAddress = true
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      runCatching { selectorManager.close() }
      selector = null
      throw if (e.looksLikeAddressInUse()) ControlPortInUseException(port, e) else e
    }
    server = bound
    log("ControlPlane", "Listening on $host:${(bound.localAddress as InetSocketAddress).port}")
    scope.launch {
      while (isActive) {
        val socket = try {
          bound.accept()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (isActive) log("ControlPlane", "accept failed: ${e.message}")
          break
        }
        launch { handleClient(socket) }
      }
    }
  }

  fun stop() {
    // Cancel the accept loop's coroutine BEFORE closing the socket: cancelling first makes a
    // suspended accept() fail with CancellationException, which the loop already rethrows
    // silently. Closing the socket first instead wakes accept() with a plain (non-cancellation)
    // "Accept failed" exception while the coroutine is still active, logging it as if it were
    // a real error on every clean shutdown.
    scope.cancel()
    runCatching { server?.close() }
    runCatching { selector?.close() }
    server = null
    selector = null
  }

  private suspend fun handleClient(socket: Socket) {
    try {
      val read = socket.openReadChannel()
      val write = socket.openWriteChannel(autoFlush = true)

      val request = try {
        withTimeout(requestTimeoutMs) { readRequest(read) }
      } catch (e: TimeoutCancellationException) {
        log("ControlPlane", "request read timed out after ${requestTimeoutMs}ms; dropping connection")
        return
      } catch (e: HttpProtocolError) {
        writeResponse(write, HttpResponse(e.status, jsonError(e.message ?: "bad request")))
        return
      } ?: return

      if (authToken != null) {
        val authHeader = request.headers["authorization"]
        val token = authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        if (token == null || token != authToken) {
          writeResponse(write, HttpResponse(401, jsonError("unauthorized: missing or invalid bearer token")))
          return
        }
      }

      val response = try {
        handle(request)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("ControlPlane", "handler error for ${request.method} ${request.path}: ${e.message}", e)
        HttpResponse(500, jsonError(e.message ?: e::class.simpleName ?: "error"))
      }
      writeResponse(write, response)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("ControlPlane", "client I/O failed: ${e.message}")
    } finally {
      runCatching { socket.close() }
    }
  }

  /** Parses the request line, headers and body. Returns null for a malformed/empty request
   *  (closed without a response, same as an unparsable request line always has been); throws
   *  [HttpProtocolError] for a well-formed-but-rejected request (oversized body, bad
   *  Content-Length, too many/too-long headers) so the caller can answer with the right status. */
  private suspend fun readRequest(read: ByteReadChannel): HttpRequest? {
    val requestLine = read.readUTF8Line() ?: return null
    val parts = requestLine.split(" ")
    if (parts.size < 2) return null
    val method = parts[0]
    val target = parts[1]

    val headers = mutableMapOf<String, String>()
    var headerCount = 0
    while (true) {
      val line = try {
        read.readUTF8Line(MAX_HEADER_LINE_BYTES) ?: break
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw HttpProtocolError(431, "header line too long")
      }
      if (line.isEmpty()) break
      headerCount++
      if (headerCount > MAX_HEADER_COUNT) {
        throw HttpProtocolError(431, "too many headers")
      }
      val idx = line.indexOf(':')
      if (idx > 0) {
        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
      }
    }

    val contentLengthHeader = headers["content-length"]
    val contentLength = if (contentLengthHeader == null) {
      0L
    } else {
      contentLengthHeader.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw HttpProtocolError(400, "invalid Content-Length")
    }
    if (contentLength > MAX_BODY_BYTES) {
      throw HttpProtocolError(413, "request body exceeds $MAX_BODY_BYTES bytes")
    }

    val body = if (contentLength > 0) {
      val bytes = ByteArray(contentLength.toInt())
      read.readFully(bytes)
      bytes.decodeToString()
    } else {
      ""
    }

    val path = target.substringBefore('?')
    val query = target.substringAfter('?', missingDelimiterValue = "")
    return HttpRequest(method, path, query, body, headers)
  }

  private suspend fun writeResponse(write: ByteWriteChannel, response: HttpResponse) {
    val bodyBytes = response.body.encodeToByteArray()
    val header = "HTTP/1.1 ${response.status} ${response.reason}\r\n" +
      "Content-Type: application/json; charset=utf-8\r\n" +
      "Content-Length: ${bodyBytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    write.writeFully(header.encodeToByteArray())
    write.writeFully(bodyBytes)
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
