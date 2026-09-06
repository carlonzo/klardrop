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
            log("LanHttpShareServer", "Landing $method ${redactPath(path)} from ${conn.peerIpv4}")
            handleLanding(conn.output, method, path, claimedToken)
          } else {
            log("LanHttpShareServer", "File $method ${redactPath(path)} from ${conn.peerIpv4}")
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

internal const val LANDING_BG = "#14161b"
internal const val LANDING_BG2 = "#1a1d24"
internal const val LANDING_BG3 = "#21252e"
internal const val LANDING_TEXT = "#ece9e4"
internal const val LANDING_MUTED = "#9a9ea8"
internal const val LANDING_ACCENT = "#f0a062"
internal const val LANDING_ACCENT_INK = "#1a1206"

internal const val KLARDROP_DROP_SVG =
  """<svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3.2c0 0 6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11Z" stroke="#f0a062" stroke-width="2.1" stroke-linejoin="round"/></svg>"""

internal fun landingCss(): String = """
  html { color-scheme: dark; background: $LANDING_BG; }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    min-height: 100vh;
    padding: 28px 20px 40px;
    background:
      radial-gradient(720px 460px at 78% -6%, rgba(240,160,98,0.16), transparent 60%),
      $LANDING_BG;
    color: $LANDING_TEXT;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  .wrap { max-width: 560px; margin: 0 auto; }
  .brand {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 22px;
  }
  .mark {
    width: 36px;
    height: 36px;
    border-radius: 10px;
    background: linear-gradient(160deg, #2b2f38, #191c22);
    border: 1px solid rgba(255,255,255,0.14);
    display: grid;
    place-items: center;
    flex: none;
  }
  .mark svg { display: block; }
  .wordmark {
    font-size: 1.35rem;
    font-weight: 700;
    letter-spacing: -0.02em;
    color: $LANDING_TEXT;
    line-height: 1.1;
  }
  .tag {
    margin-top: 2px;
    font-size: 0.8rem;
    color: $LANDING_MUTED;
  }
  .card {
    background: $LANDING_BG2;
    color: $LANDING_TEXT;
    border: 1px solid rgba(255,255,255,0.14);
    border-radius: 16px;
    padding: 22px;
  }
  h1 {
    font-size: 1.15rem;
    font-weight: 650;
    margin: 0 0 16px;
    color: $LANDING_TEXT;
  }
  textarea {
    width: 100%;
    min-height: 180px;
    padding: 12px;
    border: 1px solid rgba(255,255,255,0.14);
    border-radius: 10px;
    font-size: 1rem;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    resize: vertical;
    background: $LANDING_BG3;
    color: $LANDING_TEXT;
  }
  .actions { margin-top: 16px; display: flex; justify-content: flex-end; }
  button, .download-btn {
    background: $LANDING_ACCENT;
    color: $LANDING_ACCENT_INK;
    border: none;
    border-radius: 11px;
    padding: 12px 18px;
    font-size: 0.95rem;
    font-weight: 650;
    cursor: pointer;
    text-decoration: none;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-height: 44px;
    white-space: nowrap;
  }
  button:active, .download-btn:active { filter: brightness(0.95); }
  .file-list { list-style: none; margin: 0; padding: 0; }
  .file-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 0;
    border-bottom: 1px solid rgba(255,255,255,0.08);
    gap: 12px;
  }
  .file-item:last-child { border-bottom: none; }
  .file-info { flex: 1; min-width: 0; overflow: hidden; }
  .file-name { font-weight: 600; word-break: break-word; color: $LANDING_TEXT; }
  .file-meta { font-size: 0.85rem; color: $LANDING_MUTED; margin-top: 4px; }
""".trimIndent()

internal fun renderLandingDocument(innerBody: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<meta name="theme-color" content="$LANDING_BG">
<title>Klardrop</title>
<style>
${landingCss()}
</style>
</head>
<body>
<div class="wrap">
  <header class="brand">
    <div class="mark">$KLARDROP_DROP_SVG</div>
    <div>
      <div class="wordmark">Klardrop</div>
      <div class="tag">Shared over your Wi-Fi</div>
    </div>
  </header>
  $innerBody
</div>
</body>
</html>
""".trimIndent()

internal fun renderTextLandingHtml(text: String): String = renderLandingDocument(
  """
<div class="card">
  <h1>Shared text</h1>
  <textarea id="shared-text" readonly>${htmlEscape(text)}</textarea>
  <div class="actions">
    <button id="copy-btn" type="button" onclick="copyText()">Copy</button>
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
""".trimIndent()
)

internal fun renderFilesLandingHtml(files: List<SharedFile>, claimedToken: String): String {
  val items = buildString {
    for ((index, file) in files.withIndex()) {
      // Do not put a `download` attribute on this link. Chrome Android sends those
      // through the download manager, which does not inherit the user's "Proceed"
      // exception for our self-signed cert, so the GET never reaches us.
      append(
        """
    <li class="file-item">
      <div class="file-info">
        <div class="file-name">${htmlEscape(file.fileName)}</div>
        <div class="file-meta">${formatFileSize(file.fileSize)} &bull; ${htmlEscape(file.mimeType.ifBlank { "Unknown" })}</div>
      </div>
      <a href="/s/$claimedToken/file/$index" class="download-btn">Download</a>
    </li>
        """.trimIndent(),
      )
    }
  }
  return renderLandingDocument(
    """
<div class="card">
  <h1>Shared files</h1>
  <ul class="file-list">
$items
  </ul>
</div>
    """.trimIndent(),
  )
}
