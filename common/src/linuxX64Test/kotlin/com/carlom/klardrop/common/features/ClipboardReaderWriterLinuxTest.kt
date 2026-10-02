@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.utils.CoroutinesImpl
import com.carlom.klardrop.common.utils.execProcess
import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ClipboardReaderWriterLinuxTest {

  data class ChildProcessInfo(val pid: Int, val comm: String, val state: Char)

  private fun readProcFile(path: String): String? = memScoped {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    try {
      val bufSize = 2048
      val buffer = allocArray<ByteVar>(bufSize)
      val n = read(fd, buffer, (bufSize - 1).toULong())
      if (n > 0) {
        buffer[n.toInt()] = 0
        buffer.toKString()
      } else {
        ""
      }
    } finally {
      close(fd)
    }
  }

  private fun getChildProcesses(myPid: Int = getpid()): List<ChildProcessInfo> = memScoped {
    val children = mutableListOf<ChildProcessInfo>()
    val dir = opendir("/proc") ?: return emptyList()
    try {
      while (true) {
        val entry = readdir(dir) ?: break
        val name = entry.pointed.d_name.toKString()
        val pid = name.toIntOrNull() ?: continue
        if (pid == myPid) continue

        val statContent = readProcFile("/proc/$pid/stat") ?: continue
        val openParen = statContent.indexOf('(')
        val closeParen = statContent.lastIndexOf(')')
        if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
          val comm = statContent.substring(openParen + 1, closeParen)
          val rest = statContent.substring(closeParen + 1).trim().split(Regex("\\s+"))
          if (rest.size >= 2) {
            val state = rest[0].firstOrNull() ?: '?'
            val ppid = rest[1].toIntOrNull() ?: -1
            if (ppid == myPid) {
              children.add(ChildProcessInfo(pid, comm, state))
            }
          }
        }
      }
    } finally {
      closedir(dir)
    }
    children
  }

  @Test
  fun testClipboardRoundTrip() {
    val waylandDisplay = getenv("WAYLAND_DISPLAY")?.toKString()
    if (waylandDisplay.isNullOrEmpty()) {
      println("Skipping clipboard test: WAYLAND_DISPLAY not set")
      return
    }

    val clip = ClipboardReaderWriter()
    val savedClipboard = clip.read()
    try {
      val testString = "Klardrop-Test-${Random.nextInt()}"
      val mark = TimeSource.Monotonic.markNow()
      clip.write(testString)
      val elapsed = mark.elapsedNow()
      assertTrue(elapsed < 2.seconds, "Clipboard write took $elapsed, expected < 2s")
      val readBack = clip.read()
      assertEquals(testString, readBack)
      assertEquals(testString, clip.readForSync())
    } finally {
      if (savedClipboard.isNotEmpty()) {
        clip.write(savedClipboard)
      } else {
        try {
          execProcess(listOf("wl-copy", "--clear"), captureOutput = false)
        } catch (_: Exception) {}
      }
    }
  }

  @Test
  fun testEventDrivenClipboardSyncFlow() = runBlocking {
    val waylandDisplay = getenv("WAYLAND_DISPLAY")?.toKString()
    if (waylandDisplay.isNullOrEmpty()) {
      println("Skipping clipboard test: WAYLAND_DISPLAY not set")
      return@runBlocking
    }

    val clip = ClipboardReaderWriter()
    val savedClipboard = clip.read()

    try {
      val coroutines = CoroutinesImpl()
      val manager = ClipboardManager(coroutines, clip)

      val mutex = Mutex()
      val emitted = mutableListOf<String>()
      val collectJob = coroutines.appScope.launch {
        manager.flow.collect { item ->
          mutex.withLock { emitted.add(item) }
        }
      }

      // Wait briefly for subscription and watch process to start
      delay(300)

      // 1. wl-copy a value and assert the flow emits it within 1s
      val testValue = "Klardrop-Native-${Random.nextInt()}"
      clip.write(testValue)

      val deadline = TimeSource.Monotonic.markNow()
      var emittedTestValue = false
      while (deadline.elapsedNow() < 1.seconds) {
        val found = mutex.withLock { emitted.contains(testValue) }
        if (found) {
          emittedTestValue = true
          break
        }
        delay(50)
      }
      val allEmitted = mutex.withLock { emitted.toList() }
      assertTrue(emittedTestValue, "Flow did not emit '$testValue' within 1s. Emitted: $allEmitted")

      // Verify the long-lived watch child is running
      val initialChildren = getChildProcesses()
      val watchChild = initialChildren.find { it.comm == "wl-paste" }
      assertNotNull(watchChild, "Expected wl-paste child process while flow is collected")
      val watchPid = watchChild.pid

      // 2. Assert no wl-paste process is spawned while the clipboard is idle for 3s
      val idleStart = TimeSource.Monotonic.markNow()
      while (idleStart.elapsedNow() < 3.seconds) {
        delay(200)
        val children = getChildProcesses()
        val wlPasteChildren = children.filter { it.comm == "wl-paste" }
        assertEquals(1, wlPasteChildren.size, "Expected exactly 1 wl-paste child, found: $wlPasteChildren")
        assertEquals(watchPid, wlPasteChildren.first().pid, "wl-paste was respawned during idle period")
        val echoChildren = children.filter { it.comm == "echo" }
        assertTrue(echoChildren.isEmpty(), "Unexpected echo process spawned while idle: $echoChildren")
      }

      // 3. Cancelling the collector leaves no wl-paste child and no zombie
      collectJob.cancel()

      val cleanupDeadline = TimeSource.Monotonic.markNow()
      while (cleanupDeadline.elapsedNow() < 2.seconds) {
        delay(50)
        val children = getChildProcesses()
        val wlPasteChildren = children.filter { it.comm == "wl-paste" }
        val zombies = children.filter { it.state == 'Z' }
        if (wlPasteChildren.isEmpty() && zombies.isEmpty()) {
          break
        }
      }

      val remainingChildren = getChildProcesses()
      val remainingWlPaste = remainingChildren.filter { it.comm == "wl-paste" }
      val remainingZombies = remainingChildren.filter { it.state == 'Z' }
      assertTrue(remainingWlPaste.isEmpty(), "wl-paste child not reaped after cancel: $remainingWlPaste")
      assertTrue(remainingZombies.isEmpty(), "Zombie process remaining after cancel: $remainingZombies")
    } finally {
      // Hygiene: restore user's clipboard
      if (savedClipboard.isNotEmpty()) {
        clip.write(savedClipboard)
      } else {
        try {
          execProcess(listOf("wl-copy", "--clear"), captureOutput = false)
        } catch (_: Exception) {}
      }
    }
  }
}
