package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.cli.cliGetEnv
import com.carlom.klardrop.cli.runProcessCaptureStdout
import com.carlom.klardrop.control.resolveControlFilePath
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val OMARCHY_MENU_SELECT_PATH = "/usr/share/omarchy/bin/omarchy-menu-select"

class ShareCommand : CliktCommand(
  name = "share",
) {
  private val paths by argument(
    "paths",
    help = "Files or directories to share",
  ).multiple()

  private val to by option(
    "-t", "--to",
    help = "Target device ID (short or full)",
  )

  private val text by option(
    "--text",
    help = "Text message to share",
  )

  private val clipboard by option(
    "--clipboard",
    help = "Share current clipboard content",
  ).flag(default = false)

  override fun run() = runBlocking {
    if (paths.isEmpty() && text == null && !clipboard) {
      echo("Nothing to share: specify file path(s), --text, or --clipboard", err = true)
      throw ProgramResult(2)
    }

    val controlPath = resolveControlFilePath()
    val controlText = controlPath?.let { p ->
      if (SystemFileSystem.metadataOrNull(Path(p)) == null) null
      else runCatching { SystemFileSystem.source(Path(p)).buffered().readString() }.getOrNull()
    }
    if (controlText == null) {
      echo("Klardrop daemon is not running (control file not found). Start it with 'klardrop daemon' or 'systemctl --user start klardrop'.", err = true)
      throw ProgramResult(3)
    }

    val (port, token) = try {
      val jsonElement = Json.parseToJsonElement(controlText).jsonObject
      val p = jsonElement["port"]?.jsonPrimitive?.intOrNull ?: error("Missing 'port'")
      val tok = jsonElement["token"]?.jsonPrimitive?.content ?: error("Missing 'token'")
      p to tok
    } catch (e: Exception) {
      echo("Failed to read daemon control info from $controlPath: ${e.message}", err = true)
      throw ProgramResult(3)
    }

    val targetDevice = to ?: pickTargetDevice(port, token)

    val (endpoint, requestBody) = when {
      clipboard -> {
        "/send-clipboard" to buildJsonObject {
          put("deviceId", targetDevice)
        }
      }
      text != null -> {
        "/send-text" to buildJsonObject {
          put("deviceId", targetDevice)
          put("text", text!!)
        }
      }
      else -> {
        for (p in paths) {
          if (SystemFileSystem.metadataOrNull(Path(p)) == null) {
            echo("File not found: $p", err = true)
            throw ProgramResult(2)
          }
        }
        "/send-file" to buildJsonObject {
          put("deviceId", targetDevice)
          putJsonArray("paths") {
            for (p in paths) {
              add(JsonPrimitive(absolutePath(p)))
            }
          }
        }
      }
    }

    try {
      val (code, responseText) = httpRequest(port, token, "POST", endpoint, requestBody.toString())
      if (code == 200) {
        echo("Shared successfully")
      } else {
        echo("Failed to share (HTTP $code): $responseText", err = true)
        throw ProgramResult(1)
      }
    } catch (e: ProgramResult) {
      throw e
    } catch (e: Exception) {
      echo("Error sharing: ${e.message}", err = true)
      throw ProgramResult(1)
    }
  }

  private suspend fun pickTargetDevice(port: Int, token: String): String {
    val (code, stateBody) = try {
      httpRequest(port, token, "GET", "/state")
    } catch (e: Exception) {
      echo("Failed to query daemon for devices: ${e.message}", err = true)
      throw ProgramResult(1)
    }
    if (code != 200) {
      echo("Daemon returned HTTP $code when querying devices: $stateBody", err = true)
      throw ProgramResult(1)
    }

    val stateJson = Json.parseToJsonElement(stateBody).jsonObject
    val devices = stateJson["devices"]?.jsonArray.orEmpty().mapNotNull {
      it as? JsonObject
    }
    val pairedDevices = devices.filter { d ->
      d["paired"]?.jsonPrimitive?.booleanOrNull == true
    }

    if (pairedDevices.isEmpty()) {
      echo("No paired devices found. Pair a device first in Klardrop.", err = true)
      throw ProgramResult(1)
    }

    val options = pairedDevices.map { d ->
      val id = d["deviceId"]?.jsonPrimitive?.content
        ?: d["id"]?.jsonPrimitive?.content
        ?: ""
      val name = d["customName"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        ?: d["name"]?.jsonPrimitive?.content
        ?: id
      "$name\t$id"
    }

    val selected = pickDeviceWithMenu(options)
    if (selected == null) {
      echo("Device selection cancelled.", err = true)
      throw ProgramResult(130)
    }
    return selected.substringAfterLast('\t')
  }

  // Omarchy is the only supported target for interactive device selection: the desktop shell
  // always provides `omarchy-menu-select`. Without it (or without a terminal), --to is required.
  private fun pickDeviceWithMenu(options: List<String>): String? {
    val menuBin = when {
      isExecutable(OMARCHY_MENU_SELECT_PATH) -> OMARCHY_MENU_SELECT_PATH
      else -> which("omarchy-menu-select")
    }
    if (menuBin == null) {
      echo("omarchy-menu-select not found. Specify --to <deviceId>.", err = true)
      return null
    }

    val menuArgv = listOf(menuBin, "Share with:") + options
    val result = runProcessCaptureStdout(menuArgv, timeoutMillis = 120_000L)
    if (result == null) {
      echo("Error running omarchy-menu-select", err = true)
      return null
    }
    val (exitCode, output) = result
    return if (exitCode == 0 && output.isNotEmpty()) output else null
  }

  private fun isExecutable(path: String): Boolean =
    SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile == true

  private fun which(name: String): String? {
    val pathEnv = cliGetEnv("PATH") ?: return null
    for (dir in pathEnv.split(":")) {
      if (dir.isEmpty()) continue
      val candidate = "$dir/$name"
      if (isExecutable(candidate)) return candidate
    }
    return null
  }

  private fun absolutePath(p: String): String =
    if (p.startsWith("/")) p else "${SystemFileSystem.resolve(Path(p))}"

  private suspend fun httpRequest(
    port: Int,
    token: String,
    method: String,
    path: String,
    body: String? = null,
  ): Pair<Int, String> {
    // Selectors belong on IO, not Default (their pselect loop parks a thread per instance).
    val selectorManager = SelectorManager(Dispatchers.IO)
    try {
      val socket = aSocket(selectorManager).tcp().connect(InetSocketAddress("127.0.0.1", port))
      try {
        val write = socket.openWriteChannel(autoFlush = true)
        val read = socket.openReadChannel()
        val bodyBytes = body?.encodeToByteArray()

        val header = buildString {
          append("$method $path HTTP/1.1\r\n")
          append("Host: 127.0.0.1\r\n")
          append("Authorization: Bearer $token\r\n")
          if (bodyBytes != null) {
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
          }
          append("Connection: close\r\n\r\n")
        }
        write.writeFully(header.encodeToByteArray())
        if (bodyBytes != null) write.writeFully(bodyBytes)

        val statusLine = read.readUTF8Line() ?: return -1 to "no response from daemon"
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1

        var contentLength = 0
        while (true) {
          val line = read.readUTF8Line() ?: break
          if (line.isEmpty()) break
          val idx = line.indexOf(':')
          if (idx > 0 && line.substring(0, idx).trim().equals("content-length", ignoreCase = true)) {
            contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
          }
        }
        val responseBody = if (contentLength > 0) {
          val bytes = ByteArray(contentLength)
          read.readFully(bytes)
          bytes.decodeToString()
        } else {
          ""
        }
        return code to responseBody
      } finally {
        runCatching { socket.close() }
      }
    } finally {
      runCatching { selectorManager.close() }
    }
  }
}
