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

  private var server: ServerSocketChannel? = null

  /** Releases the lock and stops the focus server. The OS also does both on process death. */
  fun close() {
    runCatching { server?.close() }
    runCatching { lockChannel.close() }
  }

  private fun startFocusServer() {
    val address = UnixDomainSocketAddress.of(socketFile.toPath())
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    try {
      server.bind(address)
    } catch (e: Exception) {
      // Stale socket from a previous run: file locks die with the process, socket files don't.
      socketFile.delete()
      server.bind(address)
    }
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

  private fun handleFocusClient(client: SocketChannel) {
    val buffer = ByteBuffer.allocate(FOCUS.toByteArray().size)
    while (buffer.hasRemaining() && client.read(buffer) >= 0) {
    }
    if (String(buffer.array(), StandardCharsets.UTF_8) == FOCUS) {
      onFocus?.invoke()
      client.write(ByteBuffer.wrap(OK.toByteArray()))
    }
  }

  companion object {
    private const val FOCUS = "FOCUS\n"
    private const val OK = "OK\n"
    private const val FOCUS_TIMEOUT_MS = 2_000L

    /**
     * Takes the single-instance lock for [dataDir]. Returns the guard if this process is
     * the primary instance, or null if another instance is running — in which case a
     * focus request has been sent to it (best effort, 2s timeout) and the caller should exit.
     */
    fun acquire(dataDir: File = defaultDataDir()): SingleInstance? {
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
        requestFocus(File(dataDir, "instance.sock"))
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
  }
}
