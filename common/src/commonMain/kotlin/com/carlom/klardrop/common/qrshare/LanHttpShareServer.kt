package com.carlom.klardrop.common.qrshare

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import dev.whyoleg.cryptography.random.CryptographyRandom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

open class LanHttpShareServer(
  private val coroutines: Coroutines,
  private val fileManager: FileManager,
  private val tls: LanTlsListener,
  private val clock: Clock = Clock.System,
) {
  sealed interface DownloadEvent {
    data class Started(val connectionId: String, val index: Int, val fileName: String, val totalBytes: Long) : DownloadEvent
    data class Progress(val connectionId: String, val bytesTransferred: Long, val totalBytes: Long) : DownloadEvent
    data class Ended(val connectionId: String, val success: Boolean) : DownloadEvent
    data class LandingHit(val path: String) : DownloadEvent
    data class TokenRotated(val url: String) : DownloadEvent
  }

  private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
  open val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

  internal val tokenTable = QrTokenTable(clock = clock)

  private var serverScope: CoroutineScope? = null
  private var currentPayload: QrSharePayload? = null
  private var advertisedIpv4: String = ""
  private var boundPort: Int = 0

  open suspend fun start(payload: QrSharePayload, waitingToken: String, ipv4: String, port: Int = 0): Bound {
    stop()
    currentPayload = payload
    advertisedIpv4 = ipv4

    tokenTable.reset()
    tokenTable.setWaiting(waitingToken)

    val bound = tls.bind(ipv4, port)
    boundPort = bound.port

    val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
    serverScope = scope

    scope.launch {
      try {
        tls.incoming().collect { conn ->
          launch {
            handleConnection(conn)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("LanHttpShareServer", "Error accepting TLS connection: ${e.message}")
      }
    }

    scope.launch {
      while (isActive) {
        delay(1000)
        tokenTable.evictExpired()
      }
    }

    log("LanHttpShareServer", "Started share server on $ipv4:${bound.port}")
    return bound
  }

  open fun stop() {
    serverScope?.cancel()
    serverScope = null
    tokenTable.reset()
    tls.close()
    log("LanHttpShareServer", "Stopped share server")
  }

  open fun dropWaitingToken() {
    tokenTable.dropWaiting()
  }

  private suspend fun handleConnection(conn: TlsConnection) {
    var completedNormally = false
    try {
      @Suppress("DEPRECATION")
      val requestData = try {
        withTimeout(3000) {
          readHttpRequest(conn.input)
        }
      } catch (e: TimeoutCancellationException) {
        log("LanHttpShareServer", "Header read timed out for peer ${conn.peerIpv4}")
        return
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("LanHttpShareServer", "Failed to read HTTP request: ${e.message}")
        return
      } ?: return

      val (requestLine, _) = requestData
      val parts = requestLine.split(" ")
      if (parts.size < 2) return
      val method = parts[0]
      val target = parts[1]

      if (method != "GET" && method != "HEAD") {
        respondMethodNotAllowed(conn.output)
        completedNormally = true
        return
      }

      val path = extractPath(target)
      val accessResult = tokenTable.resolveAccess(path, conn.peerIpv4)

      when (accessResult) {
        is TokenAccessResult.Denied -> {
          log("LanHttpShareServer", "Denied access to ${redactPath(path)} from ${conn.peerIpv4}")
          respondNotFound(conn.output, method == "HEAD")
        }
        is TokenAccessResult.Capped -> {
          log("LanHttpShareServer", "Claim capped (503) for peer ${conn.peerIpv4}")
          respondServiceUnavailable(conn.output, method == "HEAD")
        }
        is TokenAccessResult.Allowed -> {
          val claimedToken = accessResult.token
          if (accessResult.isNewClaim) {
            log("LanHttpShareServer", "Token claimed by ${conn.peerIpv4}, rotating waiting token")
            val newWaitingToken = generateShareToken()
            tokenTable.setWaiting(newWaitingToken)
            val newUrl = "https://$advertisedIpv4:$boundPort/s/$newWaitingToken"
            _events.emit(DownloadEvent.TokenRotated(newUrl))
          }

          if (accessResult.fileIndex == null) {
            handleLanding(conn.output, method, path, claimedToken)
          } else {
            handleFile(conn.output, method, claimedToken, accessResult.fileIndex)
          }
        }
      }
      completedNormally = true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("LanHttpShareServer", "Error handling client connection: ${e.message}")
    } finally {
      if (completedNormally) {
        runCatching { conn.output.flushAndClose() }
      } else {
        runCatching { conn.close() }
      }
    }
  }

  private suspend fun handleLanding(
    output: ByteWriteChannel,
    method: String,
    path: String,
    claimedToken: String,
  ) {
    val html = when (val p = currentPayload) {
      is QrSharePayload.Text -> renderTextLandingHtml(p.text)
      is QrSharePayload.Files -> renderFilesLandingHtml(p.files, claimedToken)
      null -> ""
    }
    val bodyBytes = html.encodeToByteArray()
    val header = "HTTP/1.1 200 OK\r\n" +
      "Content-Type: text/html; charset=utf-8\r\n" +
      "Content-Length: ${bodyBytes.size}\r\n" +
      "Cache-Control: no-store\r\n" +
      "X-Content-Type-Options: nosniff\r\n" +
      "Connection: close\r\n\r\n"

    output.writeStringUtf8(header)
    if (method == "GET") {
      output.writeFully(bodyBytes)
    }
    output.flush()

    tokenTable.bumpActivity(claimedToken)
    _events.emit(DownloadEvent.LandingHit(path))
  }

  private suspend fun handleFile(
    output: ByteWriteChannel,
    method: String,
    claimedToken: String,
    fileIndex: Int,
  ) {
    val files = (currentPayload as? QrSharePayload.Files)?.files
    if (files == null || fileIndex !in files.indices) {
      respondNotFound(output, method == "HEAD")
      return
    }

    val file = files[fileIndex]

    if (method == "HEAD") {
      val headers = buildFileHeaders(file, isHead = true)
      output.writeStringUtf8(headers)
      output.flush()
      tokenTable.bumpActivity(claimedToken)
      return
    }

    val canDownload = tokenTable.startDownload(claimedToken)
    if (!canDownload) {
      log("LanHttpShareServer", "File download concurrency capped (503) for file $fileIndex")
      respondServiceUnavailable(output, isHead = false)
      return
    }

    val connectionId = generateConnectionId()
    _events.emit(DownloadEvent.Started(connectionId, fileIndex, file.fileName, file.fileSize))

    var success = false
    try {
      streamFile(output, file, connectionId)
      success = true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("LanHttpShareServer", "Streaming failed for file $fileIndex: ${e.message}")
      throw e
    } finally {
      tokenTable.endDownload(claimedToken)
      _events.emit(DownloadEvent.Ended(connectionId, success))
    }
  }

  private suspend fun streamFile(
    output: ByteWriteChannel,
    file: SharedFile,
    connectionId: String,
  ) {
    val useChunked = file.fileSize <= 0
    val headers = buildFileHeaders(file, isHead = false)
    output.writeStringUtf8(headers)
    output.flush()

    val bufferSize = 64 * 1024
    val buffer = ByteArray(bufferSize)
    var totalTransferred = 0L

    fileManager.getReadStreamFrom(file.file).buffered().use { source ->
      while (true) {
        val bytesToRead = if (!useChunked) {
          val remaining = file.fileSize - totalTransferred
          if (remaining <= 0) break
          minOf(bufferSize.toLong(), remaining).toInt()
        } else {
          bufferSize
        }

        val read = source.readAtMostTo(buffer, 0, bytesToRead)
        if (read <= 0) break

        if (useChunked) {
          val hexLen = read.toString(16)
          output.writeStringUtf8("$hexLen\r\n")
          output.writeFully(buffer, 0, read)
          output.writeStringUtf8("\r\n")
        } else {
          output.writeFully(buffer, 0, read)
        }
        output.flush()

        totalTransferred += read
        _events.emit(DownloadEvent.Progress(connectionId, totalTransferred, file.fileSize))
      }
    }

    if (useChunked) {
      output.writeStringUtf8("0\r\n\r\n")
      output.flush()
    }
  }

  private fun buildFileHeaders(file: SharedFile, isHead: Boolean): String {
    val useChunked = file.fileSize <= 0
    return buildString {
      append("HTTP/1.1 200 OK\r\n")
      append("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n")
      if (useChunked) {
        if (!isHead) {
          append("Transfer-Encoding: chunked\r\n")
        }
      } else {
        append("Content-Length: ${file.fileSize}\r\n")
      }
      append("Content-Disposition: ${contentDispositionHeader(file.fileName)}\r\n")
      append("Accept-Ranges: none\r\n")
      append("Cache-Control: no-store\r\n")
      append("X-Content-Type-Options: nosniff\r\n")
      append("Connection: close\r\n\r\n")
    }
  }

  private suspend fun respondNotFound(output: ByteWriteChannel, isHead: Boolean = false) {
    val body = "Not found.\n"
    val bodyBytes = body.encodeToByteArray()
    val header = "HTTP/1.1 404 Not Found\r\n" +
      "Content-Type: text/plain; charset=utf-8\r\n" +
      "Content-Length: ${bodyBytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    output.writeStringUtf8(header)
    if (!isHead) {
      output.writeFully(bodyBytes)
    }
    output.flush()
  }

  private suspend fun respondServiceUnavailable(output: ByteWriteChannel, isHead: Boolean = false) {
    val body = "Service Unavailable\n"
    val bodyBytes = body.encodeToByteArray()
    val header = "HTTP/1.1 503 Service Unavailable\r\n" +
      "Retry-After: 2\r\n" +
      "Content-Type: text/plain; charset=utf-8\r\n" +
      "Content-Length: ${bodyBytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    output.writeStringUtf8(header)
    if (!isHead) {
      output.writeFully(bodyBytes)
    }
    output.flush()
  }

  private suspend fun respondMethodNotAllowed(output: ByteWriteChannel, isHead: Boolean = false) {
    val body = "Method Not Allowed\n"
    val bodyBytes = body.encodeToByteArray()
    val header = "HTTP/1.1 405 Method Not Allowed\r\n" +
      "Allow: GET, HEAD\r\n" +
      "Content-Type: text/plain; charset=utf-8\r\n" +
      "Content-Length: ${bodyBytes.size}\r\n" +
      "Connection: close\r\n\r\n"
    output.writeStringUtf8(header)
    if (!isHead) {
      output.writeFully(bodyBytes)
    }
    output.flush()
  }

  @Suppress("DEPRECATION")
  private suspend fun readHttpRequest(input: ByteReadChannel): Pair<String, Map<String, String>>? {
    val requestLine = input.readUTF8Line() ?: return null
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = input.readUTF8Line() ?: break
      if (line.isEmpty()) break
      val idx = line.indexOf(':')
      if (idx > 0) {
        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
      }
    }
    return Pair(requestLine, headers)
  }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun generateShareToken(): String {
  val bytes = CryptographyRandom.nextBytes(16)
  return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
}

@OptIn(ExperimentalEncodingApi::class)
internal fun generateConnectionId(): String {
  val bytes = CryptographyRandom.nextBytes(8)
  return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
}

internal fun extractPath(target: String): String {
  val pathWithQuery = if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
    val schemeEnd = target.indexOf("://")
    val slashIdx = target.indexOf('/', startIndex = schemeEnd + 3)
    if (slashIdx >= 0) target.substring(slashIdx) else "/"
  } else {
    target
  }
  return pathWithQuery.substringBefore('?')
}

internal fun redactToken(token: String): String {
  return if (token.length <= 6) {
    "***"
  } else {
    token.take(3) + "..." + token.takeLast(3)
  }
}

internal fun redactPath(path: String): String {
  val parsed = parseSharePath(path) ?: return path
  val redacted = redactToken(parsed.token)
  return if (parsed.fileIndex != null) {
    "/s/$redacted/file/${parsed.fileIndex}"
  } else {
    "/s/$redacted"
  }
}

internal fun htmlEscape(s: String): String = buildString(s.length + 16) {
  for (c in s) {
    when (c) {
      '&' -> append("&amp;")
      '<' -> append("&lt;")
      '>' -> append("&gt;")
      '"' -> append("&quot;")
      '\'' -> append("&#39;")
      else -> append(c)
    }
  }
}

internal fun contentDispositionFileName(rawFileName: String): String {
  val stripped = rawFileName.filter { it != '"' && it != '\\' && it != '\r' && it != '\n' }.ifEmpty { "file" }

  val asciiSafe = buildString(stripped.length) {
    for (c in stripped) {
      if (c in ' '..'~' && c != '"' && c != '\\') {
        append(c)
      } else {
        append('_')
      }
    }
  }.ifEmpty { "file" }

  val bytes = stripped.encodeToByteArray()
  val rfc5987 = buildString(bytes.size * 2) {
    append("UTF-8''")
    for (b in bytes) {
      val c = b.toInt().toChar()
      if ((c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_' || c == '~') {
        append(c)
      } else {
        val hex = (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        append('%')
        append(hex)
      }
    }
  }

  return "filename=\"$asciiSafe\"; filename*=$rfc5987"
}

internal fun contentDispositionHeader(fileName: String): String {
  return "attachment; ${contentDispositionFileName(fileName)}"
}

internal fun formatFileSize(bytes: Long): String {
  if (bytes <= 0L) return "Unknown size"
  if (bytes < 1024L) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024.0) {
    val rounded = (kb * 10).toLong() / 10.0
    return "$rounded KB"
  }
  val mb = kb / 1024.0
  if (mb < 1024.0) {
    val rounded = (mb * 10).toLong() / 10.0
    return "$rounded MB"
  }
  val gb = mb / 1024.0
  val rounded = (gb * 10).toLong() / 10.0
  return "$rounded GB"
}

internal fun renderTextLandingHtml(text: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Shared Text - Klardrop</title>
<style>
  :root { color-scheme: light dark; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    margin: 0;
    padding: 24px;
    background: #f7f7f8;
    color: #1a1a1a;
    display: flex;
    justify-content: center;
  }
  @media (prefers-color-scheme: dark) {
    body { background: #121212; color: #f0f0f0; }
    .card { background: #1e1e1e; border-color: #333; }
    textarea { background: #2a2a2a; color: #f0f0f0; border-color: #444; }
  }
  .card {
    background: #ffffff;
    border: 1px solid #e0e0e0;
    border-radius: 12px;
    padding: 24px;
    max-width: 600px;
    width: 100%;
    box-shadow: 0 2px 8px rgba(0,0,0,0.05);
  }
  h1 { font-size: 1.25rem; margin-top: 0; margin-bottom: 16px; }
  textarea {
    width: 100%;
    min-height: 180px;
    padding: 12px;
    border: 1px solid #ccc;
    border-radius: 8px;
    font-size: 1rem;
    font-family: monospace;
    resize: vertical;
    box-sizing: border-box;
  }
  .actions { margin-top: 16px; display: flex; justify-content: flex-end; }
  button {
    background: #0066cc;
    color: white;
    border: none;
    border-radius: 8px;
    padding: 10px 20px;
    font-size: 1rem;
    cursor: pointer;
    font-weight: 500;
  }
  button:active { background: #0052a3; }
</style>
</head>
<body>
<div class="card">
  <h1>Shared Text</h1>
  <textarea id="shared-text" readonly>${htmlEscape(text)}</textarea>
  <div class="actions">
    <button id="copy-btn" onclick="copyText()">Copy</button>
  </div>
</div>
<script>
function copyText() {
  var area = document.getElementById('shared-text');
  var btn = document.getElementById('copy-btn');
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(area.value).then(function() {
      btn.innerText = 'Copied!';
      setTimeout(function() { btn.innerText = 'Copy'; }, 2000);
    }).catch(function() {
      fallbackCopy(area, btn);
    });
  } else {
    fallbackCopy(area, btn);
  }
}
function fallbackCopy(area, btn) {
  area.focus();
  area.select();
  try {
    var successful = document.execCommand('copy');
    if (successful) {
      btn.innerText = 'Copied!';
      setTimeout(function() { btn.innerText = 'Copy'; }, 2000);
    } else {
      btn.innerText = 'Select all';
    }
  } catch(e) {
    btn.innerText = 'Select all';
  }
}
</script>
</body>
</html>
""".trimIndent()

internal fun renderFilesLandingHtml(files: List<SharedFile>, claimedToken: String): String = buildString {
  append("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Shared Files - Klardrop</title>
<style>
  :root { color-scheme: light dark; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    margin: 0;
    padding: 24px;
    background: #f7f7f8;
    color: #1a1a1a;
    display: flex;
    justify-content: center;
  }
  @media (prefers-color-scheme: dark) {
    body { background: #121212; color: #f0f0f0; }
    .card { background: #1e1e1e; border-color: #333; }
    .file-item { border-color: #333; }
    .file-meta { color: #aaa; }
  }
  .card {
    background: #ffffff;
    border: 1px solid #e0e0e0;
    border-radius: 12px;
    padding: 24px;
    max-width: 600px;
    width: 100%;
    box-shadow: 0 2px 8px rgba(0,0,0,0.05);
  }
  h1 { font-size: 1.25rem; margin-top: 0; margin-bottom: 16px; }
  .file-list { list-style: none; margin: 0; padding: 0; }
  .file-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 0;
    border-bottom: 1px solid #eee;
    gap: 12px;
  }
  .file-item:last-child { border-bottom: none; }
  .file-info { flex: 1; min-width: 0; overflow: hidden; }
  .file-name {
    font-weight: 500;
    word-break: break-word;
  }
  .file-meta {
    font-size: 0.85rem;
    color: #666;
    margin-top: 4px;
  }
  .download-btn {
    display: inline-block;
    background: #0066cc;
    color: white;
    text-decoration: none;
    border-radius: 8px;
    padding: 8px 16px;
    font-size: 0.9rem;
    font-weight: 500;
    white-space: nowrap;
  }
  .download-btn:hover { background: #0052a3; }
</style>
</head>
<body>
<div class="card">
  <h1>Shared Files</h1>
  <ul class="file-list">
""".trimIndent())

  for ((index, file) in files.withIndex()) {
    append("""
    <li class="file-item">
      <div class="file-info">
        <div class="file-name">${htmlEscape(file.fileName)}</div>
        <div class="file-meta">${formatFileSize(file.fileSize)} &bull; ${htmlEscape(file.mimeType.ifBlank { "Unknown" })}</div>
      </div>
      <a href="/s/$claimedToken/file/$index" class="download-btn" download>Download</a>
    </li>
""".trimIndent())
  }

  append("""
  </ul>
</div>
</body>
</html>
""".trimIndent())
}
