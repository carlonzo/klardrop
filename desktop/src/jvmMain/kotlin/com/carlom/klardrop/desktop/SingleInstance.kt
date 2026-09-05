package com.carlom.klardrop.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

/**
 * Ensures only one Klardrop desktop instance runs per data dir.
 *
 * The primary instance holds an exclusive OS file lock on `<dataDir>/instance.lock` for
 * its lifetime — the kernel releases it automatically on process death, so a SIGKILL
 * never leaves a stale lock — and serves a Unix domain socket at
 * `<dataDir>/instance.sock`. A second launch fails to take the lock, sends `FOCUS\n`
 * over the socket, and exits; the primary invokes [onFocus] (wired in Main.kt to raise
 * the Compose window) and replies `OK\n`.
 */
class SingleInstance private constructor(
  private val lockChannel: FileChannel,
  private val socketFile: File,
) {

  /** Invoked on the focus-server thread when a second instance asks us to come forward. */
  @Volatile
  var onFocus: (() -> Unit)? = null

  /** Invoked on the focus-server thread when a second instance sends files to share. */
  @Volatile
  var onSendFiles: ((List<String>) -> Unit)? = null

  private var server: ServerSocketChannel? = null

  /** Releases the lock and stops the focus server. The OS also does both on process death. */
  fun close() {
    runCatching { server?.close() }
    runCatching { lockChannel.close() }
  }

  /**
   * Opens the focus socket. Best effort: we already hold the lock and ARE the primary
   * instance, so a socket we cannot bind (no UNIX-socket support on this filesystem, a
   * path over the ~104-char sun_path limit, a permission problem) costs us the
   * focus-an-existing-window nicety and nothing else. Letting it throw would take the
   * whole app down at startup over a convenience feature.
   */
  private fun startFocusServer() {
    val server = openFocusSocket() ?: return
    this.server = server
    Thread({
      while (true) {
        val client = try {
          server.accept()
        } catch (e: Exception) {
          break // server closed
        }
        client.use { handleFocusClient(it) }
      }
    }, "klardrop-single-instance").apply {
      isDaemon = true
      start()
    }
  }

  /**
   * Opens the focus socket, or returns null when it cannot be had. UnixDomainSocketAddress.of
   * rejects a path over the platform's sun_path limit, and the bind can fail for reasons that
   * have nothing to do with a stale file, so both live inside the guard.
   */
  private fun openFocusSocket(): ServerSocketChannel? = runCatching {
    val address = UnixDomainSocketAddress.of(socketFile.toPath())
    val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    try {
      try {
        channel.bind(address)
      } catch (e: Exception) {
        // Stale socket from a previous run: file locks die with the process, socket files
        // don't. Nothing live can own this one — we hold the lock.
        socketFile.delete()
        channel.bind(address)
      }
    } catch (e: Exception) {
      runCatching { channel.close() }
      throw e
    }
    channel
  }.onFailure {
    println("Klardrop: single-instance focus socket unavailable (${it.message}); continuing without it")
  }.getOrNull()

  private fun handleFocusClient(client: SocketChannel) {
    val baos = java.io.ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(1024)
    while (client.read(buffer) > 0) {
      buffer.flip()
      baos.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
      buffer.clear()
      val current = baos.toString(StandardCharsets.UTF_8.name())
      if (current.startsWith(FOCUS) || current.endsWith("\n\n")) {
        break
      }
    }
    val message = baos.toString(StandardCharsets.UTF_8.name())
    when {
      message.startsWith(FOCUS) -> {
        onFocus?.invoke()
        client.write(ByteBuffer.wrap(OK.toByteArray()))
      }
      message.startsWith(SEND_PREFIX) -> {
        val paths = message.removePrefix(SEND_PREFIX)
          .trim()
          .lines()
          .map { it.trim() }
          .filter { it.isNotEmpty() }
        onFocus?.invoke()
        onSendFiles?.invoke(paths)
        client.write(ByteBuffer.wrap(OK.toByteArray()))
      }
    }
  }

  companion object {
    private const val FOCUS = "FOCUS\n"
    private const val SEND_PREFIX = "SEND\n"
    private const val OK = "OK\n"
    private const val FOCUS_TIMEOUT_MS = 2_000L

    /**
     * Takes the single-instance lock for [dataDir]. Returns the guard if this process is
     * the primary instance, or null if another instance is running — in which case a
     * focus request (or send files request) has been sent to it (best effort, 2s timeout) and the caller should exit.
     */
    fun acquire(
      dataDir: File = defaultDataDir(),
      sendFiles: List<String> = emptyList(),
    ): SingleInstance? {
      dataDir.mkdirs()
      // Closing the channel closes the underlying RandomAccessFile (bidirectionally linked),
      // so holding the channel open holds the lock.
      val lockChannel = RandomAccessFile(File(dataDir, "instance.lock"), "rw").channel
      val lock = try {
        lockChannel.tryLock()
      } catch (e: OverlappingFileLockException) {
        null // another instance in this JVM holds it (in-process test path)
      }
      if (lock == null) {
        lockChannel.close()
        if (sendFiles.isNotEmpty()) {
          requestSend(File(dataDir, "instance.sock"), sendFiles)
        } else {
          requestFocus(File(dataDir, "instance.sock"))
        }
        return null
      }
      return SingleInstance(lockChannel, File(dataDir, "instance.sock")).also { it.startFocusServer() }
    }

    private fun defaultDataDir(): File {
      // Mirrors the data-dir resolution in InternalPlatformDependencies.trustStorage():
      // the override roots the whole data dir, otherwise the traditional ~/.klardrop.
      val override = System.getProperty("klardrop.data.dir") ?: System.getenv("KLARDROP_HOME")
      return if (override != null) File(override) else File(System.getProperty("user.home"), ".klardrop")
    }

    /** Sends FOCUS to the primary instance and waits for OK. Best effort; never throws. */
    private fun requestFocus(socketFile: File) {
      runCatching {
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketFile.toPath()))
        channel.use {
          it.write(ByteBuffer.wrap(FOCUS.toByteArray()))
          // Blocking SocketChannel reads have no read timeout; poll via a non-blocking
          // selector so a wedged primary can't hang the second launch.
          it.configureBlocking(false)
          Selector.open().use { selector ->
            it.register(selector, java.nio.channels.SelectionKey.OP_READ)
            val buffer = ByteBuffer.allocate(OK.toByteArray().size)
            while (buffer.hasRemaining()) {
              if (selector.select(FOCUS_TIMEOUT_MS) == 0) return
              if (it.read(buffer) < 0) break
            }
          }
        }
      }
    }

    /** Sends SEND with file paths to the primary instance and waits for OK. Best effort; never throws. */
    private fun requestSend(socketFile: File, files: List<String>) {
      runCatching {
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(socketFile.toPath()))
        channel.use {
          val payload = "$SEND_PREFIX${files.joinToString("\n")}\n\n"
          it.write(ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8)))
          it.configureBlocking(false)
          Selector.open().use { selector ->
            it.register(selector, java.nio.channels.SelectionKey.OP_READ)
            val buffer = ByteBuffer.allocate(OK.toByteArray().size)
            while (buffer.hasRemaining()) {
              if (selector.select(FOCUS_TIMEOUT_MS) == 0) return
              if (it.read(buffer) < 0) break
            }
          }
        }
      }
    }
  }
}
