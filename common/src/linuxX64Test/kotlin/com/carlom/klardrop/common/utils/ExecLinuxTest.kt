@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.utils

import kotlinx.cinterop.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ExecLinuxTest {

  data class ProcessInfo(val pid: Int, val comm: String, val state: Char, val ppid: Int, val pgrp: Int)

  private fun readProc(path: String): String? = memScoped {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null
    try {
      val buf = allocArray<ByteVar>(2048)
      val n = read(fd, buf, 2047u)
      if (n > 0) {
        buf[n.toInt()] = 0
        buf.toKString()
      } else {
        ""
      }
    } finally {
      close(fd)
    }
  }

  private fun getAllProcesses(): List<ProcessInfo> = memScoped {
    val procs = mutableListOf<ProcessInfo>()
    val dir = opendir("/proc") ?: return emptyList()
    try {
      while (true) {
        val entry = readdir(dir) ?: break
        val name = entry.pointed.d_name.toKString()
        val pid = name.toIntOrNull() ?: continue
        val statContent = readProc("/proc/$pid/stat") ?: continue
        val openParen = statContent.indexOf('(')
        val closeParen = statContent.lastIndexOf(')')
        if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
          val comm = statContent.substring(openParen + 1, closeParen)
          val fields = statContent.substring(closeParen + 1).trim().split(Regex("\\s+"))
          if (fields.size >= 3) {
            val state = fields[0].firstOrNull() ?: '?'
            val ppid = fields[1].toIntOrNull() ?: -1
            val pgrp = fields[2].toIntOrNull() ?: -1
            procs.add(ProcessInfo(pid, comm, state, ppid, pgrp))
          }
        }
      }
    } finally {
      closedir(dir)
    }
    procs
  }

  @Test
  fun testExecStdout() {
    val result = execProcess(listOf("echo", "hello klardrop"))
    assertEquals(0, result.exitCode)
    assertEquals("hello klardrop", result.stdoutString.trim())
  }

  @Test
  fun testExecExitCode() {
    val result = execProcess(listOf("false"))
    assertEquals(1, result.exitCode)
  }

  @Test
  fun testExecStdin() {
    val input = "piped data\n"
    val result = execProcess(listOf("cat"), stdin = input.encodeToByteArray())
    assertEquals(0, result.exitCode)
    assertEquals(input, result.stdoutString)
  }

  @Test
  fun testExecTimeout() {
    val start = TimeSource.Monotonic.markNow()
    assertFailsWith<ExecTimeoutException> {
      execProcess(listOf("sleep", "5"), timeoutMillis = 150)
    }
    val elapsed = start.elapsedNow().inWholeMilliseconds
    assertTrue(elapsed < 2000, "Should have timed out quickly, took ${elapsed}ms")
  }

  @Test
  fun testExecCaptureOutputFalse() {
    val result = execProcess(listOf("echo", "hello"), captureOutput = false)
    assertEquals(0, result.exitCode)
    assertEquals(0, result.stdout.size)
    assertEquals(0, result.stderr.size)
  }

  @Test
  fun testFdInheritanceClosed() {
    // Open an extra file descriptor WITHOUT O_CLOEXEC in the parent
    val tempFd = open("/dev/null", O_RDONLY)
    assertTrue(tempFd >= 3, "tempFd should be >= 3, got $tempFd")
    val extraFd = 42
    val dupRet = dup2(tempFd, extraFd)
    assertTrue(dupRet == extraFd, "dup2 failed: $dupRet")
    close(tempFd)

    try {
      repeat(2) { pass ->
        if (pass == 1) forceCloseFallbackForTest = true
        try {
          // 1. Test execProcess
          val result = execProcess(listOf("ls", "/proc/self/fd"))
          assertEquals(0, result.exitCode)
          val fds = result.stdoutString.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
          // Child sees 0, 1, 2, plus the fd that ls itself opens (3)
          assertTrue("0" in fds, "Child should have stdin (0) [pass=$pass]")
          assertTrue("1" in fds, "Child should have stdout (1) [pass=$pass]")
          assertTrue("2" in fds, "Child should have stderr (2) [pass=$pass]")
          assertTrue(!fds.contains(extraFd.toString()), "Child inherited extraFd ($extraFd) [pass=$pass]")
          val extraChildFds = fds.filter { it !in setOf("0", "1", "2") }
          assertEquals(1, extraChildFds.size, "Child should see only 0/1/2 plus the fd ls opened, but saw: $extraChildFds [pass=$pass]")

          // 2. Test execStreamingLines
          val streamingFds = mutableListOf<String>()
          runBlocking {
            execStreamingLines(listOf("ls", "/proc/self/fd")).collect { line ->
              streamingFds.addAll(line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
            }
          }
          val streamingSet = streamingFds.toSet()
          assertTrue("0" in streamingSet, "Streaming child should have stdin (0) [pass=$pass]")
          assertTrue("1" in streamingSet, "Streaming child should have stdout (1) [pass=$pass]")
          assertTrue("2" in streamingSet, "Streaming child should have stderr (2) [pass=$pass]")
          assertTrue(!streamingSet.contains(extraFd.toString()), "Streaming child inherited extraFd ($extraFd) [pass=$pass]")
          val extraStreamingFds = streamingSet.filter { it !in setOf("0", "1", "2") }
          assertEquals(1, extraStreamingFds.size, "Streaming child should see only 0/1/2 plus the fd ls opened, but saw: $extraStreamingFds [pass=$pass]")
        } finally {
          if (pass == 1) forceCloseFallbackForTest = false
        }
      }
    } finally {
      close(extraFd)
    }
  }

  /**
   * Verifies that a UTF-8 multibyte sequence split across two separate writes is decoded
   * correctly. Without byte accumulation (old code cast each byte to Char), é (U+00E9,
   * bytes 0xC3 0xA9) would be emitted as two replacement characters instead of "é".
   *
   * printf writes the first byte of é (\303), then sleeps 50 ms, then writes the rest.
   * The tiny sleep forces two distinct read()s in the streaming loop.
   */
  @Test
  fun testStreamingUtf8SplitAcrossReads() = runBlocking {
    val sh = findExecutable("sh") ?: run {
      println("SKIP testStreamingUtf8SplitAcrossReads: sh not found on PATH")
      return@runBlocking
    }

    // é = U+00E9 = 0xC3 0xA9. Two separate printf calls with a tiny sleep force two read()s.
    // The single-quotes around the printf arguments ensure the octal escapes reach sh literally.
    val lines = mutableListOf<String>()
    execStreamingLines(listOf(sh, "-c", "printf '\\303'; sleep 0.05; printf '\\251\\ncaf\\303\\251\\n'"))
      .collect { lines.add(it) }

    assertEquals(listOf("é", "café"), lines,
      "UTF-8 multibyte sequence split across reads must be decoded correctly")
  }


  private fun findExecutable(name: String): String? {
    val path = getenv("PATH")?.toKString() ?: return null
    for (dir in path.split(':')) {
      val candidate = "$dir/$name"
      if (access(candidate, F_OK) == 0) return candidate
    }
    return null
  }

  @Test
  fun testStreamingCancellationReturnsWithinOneSecondAndLeavesNoZombies() = runBlocking {
    val sh = findExecutable("sh")
      ?: error("Required 'sh' not found on PATH")

    val received = Channel<String>(Channel.UNLIMITED)
    val flow = execStreamingLines(listOf(sh, "-c", "sleep 8 & echo x; wait"))
    val myPid = getpid()

    val job = launch {
      flow.collect { received.send(it) }
    }

    // Wait until child emits "x"
    val x = withTimeoutOrNull(3000) { received.receive() }
    assertEquals("x", x)

    // Record processes before cancel
    val beforeProcs = getAllProcesses()
    val shChild = beforeProcs.find { it.ppid == myPid && it.comm == "sh" }
    val childPgrp = shChild?.pgrp

    // Measure cancellation duration
    val mark = TimeSource.Monotonic.markNow()
    job.cancelAndJoin()
    val elapsed = mark.elapsedNow()

    assertTrue(elapsed < 1.seconds, "Cancellation took $elapsed, expected < 1s")

    // Brief grace period for OS reap
    delay(100)

    val afterProcs = getAllProcesses()
    val remainingDirect = afterProcs.filter { it.ppid == myPid && (it.comm == "sh" || it.comm == "sleep") }
    val zombies = afterProcs.filter { it.ppid == myPid && it.state == 'Z' }
    assertTrue(remainingDirect.isEmpty(), "Direct child processes remaining: $remainingDirect")
    assertTrue(zombies.isEmpty(), "Zombies remaining: $zombies")

    if (childPgrp != null && childPgrp != myPid) {
      val groupMembers = afterProcs.filter { it.pgrp == childPgrp }
      assertTrue(groupMembers.isEmpty(), "Grandchildren/group processes remaining in pgrp $childPgrp: $groupMembers")
    }
  }
}
