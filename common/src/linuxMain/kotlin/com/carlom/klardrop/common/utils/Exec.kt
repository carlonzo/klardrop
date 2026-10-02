@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.utils

import com.carlom.klardrop.common.posix.spawn.KLARDROP_POSIX_SPAWN_SETPGROUP
import com.carlom.klardrop.common.posix.spawn.klardrop_has_closefrom_np
import com.carlom.klardrop.common.posix.spawn.klardrop_pipe2
import com.carlom.klardrop.common.posix.spawn.klardrop_spawn_file_actions_addclose
import com.carlom.klardrop.common.posix.spawn.klardrop_spawn_file_actions_addclosefrom_np
import com.carlom.klardrop.common.posix.spawn.klardrop_spawn_file_actions_adddup2
import com.carlom.klardrop.common.posix.spawn.klardrop_spawn_file_actions_addopen
import com.carlom.klardrop.common.posix.spawn.klardrop_spawn_file_actions_destroy
import com.carlom.klardrop.common.posix.spawn.klardrop_spawn_file_actions_init
import com.carlom.klardrop.common.posix.spawn.klardrop_spawn_file_actions_t
import com.carlom.klardrop.common.posix.spawn.klardrop_spawnattr_destroy
import com.carlom.klardrop.common.posix.spawn.klardrop_spawnattr_init
import com.carlom.klardrop.common.posix.spawn.klardrop_spawnattr_setflags
import com.carlom.klardrop.common.posix.spawn.klardrop_spawnattr_setpgroup
import com.carlom.klardrop.common.posix.spawn.klardrop_spawnattr_t
import com.carlom.klardrop.common.posix.spawn.klardrop_spawnp
import com.carlom.klardrop.common.posix.spawn.klardrop_wexitstatus
import com.carlom.klardrop.common.posix.spawn.klardrop_wifexited
import com.carlom.klardrop.common.posix.spawn.klardrop_wifsignaled
import com.carlom.klardrop.common.posix.spawn.klardrop_wtermsig
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.*
import kotlin.time.TimeSource

private fun pipe2(fds: CPointer<IntVar>, flags: Int): Int = klardrop_pipe2(fds, flags)

class ExecException(message: String) : Exception(message)
class ExecTimeoutException(message: String) : Exception(message)

data class ExecResult(
  val exitCode: Int,
  val stdout: ByteArray,
  val stderr: ByteArray = ByteArray(0),
) {
  val stdoutString: String get() = stdout.decodeToString()
  val stderrString: String get() = stderr.decodeToString()
}

private val ignoreSigpipeOnce: Unit by lazy {
  signal(SIGPIPE, SIG_IGN)
  Unit
}

/** Set to true in tests to force the /proc/self/fd fallback path regardless of glibc version. */
internal var forceCloseFallbackForTest = false

/**
 * Registers file-action closes for every fd >= [from] that is currently open in this process,
 * skipping [skipFds]. This is the slow fallback for glibc < 2.34 where
 * posix_spawn_file_actions_addclosefrom_np is absent.
 *
 * Called from within a memScoped block; [actions] lifetime is managed by the caller.
 */
private fun addCloseFallback(
  actions: CPointer<klardrop_spawn_file_actions_t>,
  from: Int,
  skipFds: Set<Int>,
) {
  val dir = opendir("/proc/self/fd")
  if (dir == null) {
    log("Exec", "addCloseFallback: opendir(\"/proc/self/fd\") failed (errno ${posix_errno()}); child may inherit file descriptors")
    return
  }
  try {
    val selfDirFd = dirfd(dir)
    while (true) {
      val entry = readdir(dir) ?: break
      val name = entry.pointed.d_name.toKString()
      val fd = name.toIntOrNull() ?: continue
      // Skip the dirfd itself (not visible to the child) and any fds handled by adddup2/addclose.
      if (fd >= from && fd != selfDirFd && fd !in skipFds) {
        klardrop_spawn_file_actions_addclose(actions, fd)
      }
    }
  } finally {
    closedir(dir)
  }
}

/**
 * Adds close-from-fd-[from] to [actions], using the fast glibc >= 2.34 path when available
 * and the /proc/self/fd enumeration fallback on older glibc. [skipFds] lists fds that the
 * child will need and must not be closed (pipe ends being dup2'd into stdio, etc.).
 */
internal fun addClosefromOrFallback(
  actions: CPointer<klardrop_spawn_file_actions_t>,
  from: Int,
  skipFds: Set<Int> = emptySet(),
) {
  if (!forceCloseFallbackForTest && klardrop_has_closefrom_np() != 0) {
    val ret = klardrop_spawn_file_actions_addclosefrom_np(actions, from)
    if (ret != 0) {
      log("Exec", "klardrop_spawn_file_actions_addclosefrom_np failed (errno $ret); falling back to /proc/self/fd enumeration")
      addCloseFallback(actions, from, skipFds)
    }
  } else {
    addCloseFallback(actions, from, skipFds)
  }
}


/**
 * Runs an external process with explicit argv (no shell) using posix_spawnp.
 * Optionally feeds [stdin], captures [stdout] and [stderr], and terminates the child
 * if execution exceeds [timeoutMillis].
 *
 * Note: stdin is written fully before stdout is read, so it's only safe for small stdin
 * (a child that writes >64KB before draining stdin would deadlock). All current callers
 * pass small stdin.
 */
fun execProcess(
  argv: List<String>,
  stdin: ByteArray? = null,
  timeoutMillis: Long = 10_000L,
  captureOutput: Boolean = true,
  onPid: ((Int) -> Unit)? = null,
): ExecResult {
  require(argv.isNotEmpty()) { "argv must not be empty" }
  ignoreSigpipeOnce

  return memScoped {
    val stdinPipe = allocArray<IntVar>(2)
    val stdoutPipe = allocArray<IntVar>(2)
    val stderrPipe = allocArray<IntVar>(2)

    if (pipe2(stdinPipe, O_CLOEXEC) != 0) throw ExecException("Failed to create stdin pipe")
    if (captureOutput) {
      if (pipe2(stdoutPipe, O_CLOEXEC) != 0) {
        close(stdinPipe[0]); close(stdinPipe[1])
        throw ExecException("Failed to create stdout pipe")
      }
      if (pipe2(stderrPipe, O_CLOEXEC) != 0) {
        close(stdinPipe[0]); close(stdinPipe[1])
        close(stdoutPipe[0]); close(stdoutPipe[1])
        throw ExecException("Failed to create stderr pipe")
      }
    }

    val actions = alloc<klardrop_spawn_file_actions_t>()
    klardrop_spawn_file_actions_init(actions.ptr)

    // Stdin: child reads from stdinPipe[0]
    klardrop_spawn_file_actions_adddup2(actions.ptr, stdinPipe[0], STDIN_FILENO)
    klardrop_spawn_file_actions_addclose(actions.ptr, stdinPipe[0])
    klardrop_spawn_file_actions_addclose(actions.ptr, stdinPipe[1])

    if (captureOutput) {
      // Stdout: child writes to stdoutPipe[1]
      klardrop_spawn_file_actions_adddup2(actions.ptr, stdoutPipe[1], STDOUT_FILENO)
      klardrop_spawn_file_actions_addclose(actions.ptr, stdoutPipe[0])
      klardrop_spawn_file_actions_addclose(actions.ptr, stdoutPipe[1])

      // Stderr: child writes to stderrPipe[1]
      klardrop_spawn_file_actions_adddup2(actions.ptr, stderrPipe[1], STDERR_FILENO)
      klardrop_spawn_file_actions_addclose(actions.ptr, stderrPipe[0])
      klardrop_spawn_file_actions_addclose(actions.ptr, stderrPipe[1])
    } else {
      klardrop_spawn_file_actions_addopen(actions.ptr, STDOUT_FILENO, "/dev/null", O_WRONLY, 0u)
      klardrop_spawn_file_actions_addopen(actions.ptr, STDERR_FILENO, "/dev/null", O_WRONLY, 0u)
    }

    // Close all inherited fds >= 3 in the child. Fast path on glibc >= 2.34;
    // falls back to /proc/self/fd enumeration on older glibc. Skip the pipe fds
    // that are already handled by the explicit adddup2/addclose actions above.
    val pipeFds = if (captureOutput) {
      setOf(stdinPipe[0], stdinPipe[1], stdoutPipe[0], stdoutPipe[1], stderrPipe[0], stderrPipe[1])
    } else {
      setOf(stdinPipe[0], stdinPipe[1])
    }
    addClosefromOrFallback(actions.ptr, 3, pipeFds)


    val cArgs = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
    for (i in argv.indices) {
      cArgs[i] = argv[i].cstr.ptr
    }
    cArgs[argv.size] = null

    val pid = alloc<pid_tVar>()
    val spawnRet = klardrop_spawnp(pid.ptr, argv[0], actions.ptr, null, cArgs, __environ)
    klardrop_spawn_file_actions_destroy(actions.ptr)

    if (spawnRet != 0) {
      close(stdinPipe[0]); close(stdinPipe[1])
      if (captureOutput) {
        close(stdoutPipe[0]); close(stdoutPipe[1])
        close(stderrPipe[0]); close(stderrPipe[1])
      }
      throw ExecException("posix_spawnp failed for '${argv[0]}' (errno $spawnRet)")
    }

    val childPid = pid.value
    onPid?.invoke(childPid)

    // Parent closes child ends
    close(stdinPipe[0])
    if (captureOutput) {
      close(stdoutPipe[1])
      close(stderrPipe[1])
    }

    // Feed stdin if provided
    val stdinFd = stdinPipe[1]
    if (stdin != null && stdin.isNotEmpty()) {
      try {
        var written = 0
        stdin.usePinned { pinned ->
          while (written < stdin.size) {
            val res = write(stdinFd, pinned.addressOf(written), (stdin.size - written).toULong())
            if (res <= 0) break
            written += res.toInt()
          }
        }
      } finally {
        close(stdinFd)
      }
    } else {
      close(stdinFd)
    }

    val status = alloc<IntVar>()
    fun computeExitCode(rawStatus: Int): Int =
      if (klardrop_wifexited(rawStatus) != 0) {
        klardrop_wexitstatus(rawStatus)
      } else if (klardrop_wifsignaled(rawStatus) != 0) {
        128 + klardrop_wtermsig(rawStatus)
      } else {
        rawStatus
      }

    if (!captureOutput) {
      val timeMark = TimeSource.Monotonic.markNow()
      var timedOut = false

      while (true) {
        val ret = waitpid(childPid, status.ptr, WNOHANG)
        if (ret == childPid) {
          break
        } else if (ret == 0) {
          if (timeoutMillis > 0 && timeMark.elapsedNow().inWholeMilliseconds >= timeoutMillis) {
            timedOut = true
            break
          }
          usleep(5_000u)
        } else {
          val err = posix_errno()
          if (err == EINTR) continue
          break
        }
      }

      if (timedOut) {
        kill(childPid, SIGKILL)
        waitpid(childPid, status.ptr, 0)
        throw ExecTimeoutException("Process '${argv[0]}' timed out after ${timeoutMillis}ms")
      }

      return ExecResult(
        exitCode = computeExitCode(status.value),
        stdout = ByteArray(0),
        stderr = ByteArray(0),
      )
    }

    val stdoutFd = stdoutPipe[0]
    val stderrFd = stderrPipe[0]

    // Set non-blocking on read descriptors
    fcntl(stdoutFd, F_SETFL, O_NONBLOCK)
    fcntl(stderrFd, F_SETFL, O_NONBLOCK)

    val stdoutChunks = mutableListOf<ByteArray>()
    val stderrChunks = mutableListOf<ByteArray>()

    val pollFds = allocArray<pollfd>(2)
    pollFds[0].fd = stdoutFd
    pollFds[0].events = (POLLIN or POLLHUP or POLLERR).toShort()
    pollFds[1].fd = stderrFd
    pollFds[1].events = (POLLIN or POLLHUP or POLLERR).toShort()

    val bufSize = 4096
    val readBuffer = allocArray<ByteVar>(bufSize)

    val timeMark = TimeSource.Monotonic.markNow()
    var timedOut = false

    while (pollFds[0].fd >= 0 || pollFds[1].fd >= 0) {
      val remainingMs: Int = if (timeoutMillis > 0) {
        val elapsed = timeMark.elapsedNow().inWholeMilliseconds
        val left = timeoutMillis - elapsed
        if (left <= 0) {
          timedOut = true
          break
        }
        left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      } else {
        -1
      }

      val pollRet = poll(pollFds, 2u, remainingMs)
      if (pollRet < 0) {
        if (posix_errno() == EINTR) continue
        break
      }
      if (pollRet == 0 && timeoutMillis > 0) {
        timedOut = true
        break
      }

      // Read stdout
      if (pollFds[0].fd >= 0 && (pollFds[0].revents.toInt() and (POLLIN or POLLHUP or POLLERR)) != 0) {
        var drained = false
        while (!drained) {
          val n = read(stdoutFd, readBuffer, bufSize.toULong())
          if (n > 0) {
            stdoutChunks.add(readBuffer.readBytes(n.toInt()))
          } else if (n == 0L) {
            close(stdoutFd)
            pollFds[0].fd = -1
            drained = true
          } else {
            val err = posix_errno()
            if (err == EAGAIN || err == EWOULDBLOCK) {
              drained = true
            } else {
              close(stdoutFd)
              pollFds[0].fd = -1
              drained = true
            }
          }
        }
      }

      // Read stderr
      if (pollFds[1].fd >= 0 && (pollFds[1].revents.toInt() and (POLLIN or POLLHUP or POLLERR)) != 0) {
        var drained = false
        while (!drained) {
          val n = read(stderrFd, readBuffer, bufSize.toULong())
          if (n > 0) {
            stderrChunks.add(readBuffer.readBytes(n.toInt()))
          } else if (n == 0L) {
            close(stderrFd)
            pollFds[1].fd = -1
            drained = true
          } else {
            val err = posix_errno()
            if (err == EAGAIN || err == EWOULDBLOCK) {
              drained = true
            } else {
              close(stderrFd)
              pollFds[1].fd = -1
              drained = true
            }
          }
        }
      }
    }

    if (pollFds[0].fd >= 0) close(stdoutFd)
    if (pollFds[1].fd >= 0) close(stderrFd)

    if (timedOut) {
      kill(childPid, SIGKILL)
      waitpid(childPid, status.ptr, 0)
      throw ExecTimeoutException("Process '${argv[0]}' timed out after ${timeoutMillis}ms")
    }

    waitpid(childPid, status.ptr, 0)
    val exitCode = computeExitCode(status.value)

    fun mergeChunks(chunks: List<ByteArray>): ByteArray {
      val totalSize = chunks.sumOf { it.size }
      val result = ByteArray(totalSize)
      var offset = 0
      for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
      }
      return result
    }

    ExecResult(
      exitCode = exitCode,
      stdout = mergeChunks(stdoutChunks),
      stderr = mergeChunks(stderrChunks),
    )
  }
}

/**
 * Spawns an external process with explicit argv (no shell) using posix_spawnp,
 * streaming lines written to stdout as a Flow. Stdin and stderr are redirected to /dev/null.
 * When the flow collection is cancelled, the child is killed with SIGTERM
 * and reaped via waitpid (preventing zombies).
 */
fun execStreamingLines(
  argv: List<String>,
  dispatcher: CoroutineDispatcher = sharedNativeIoDispatcher,
): Flow<String> = callbackFlow {
  require(argv.isNotEmpty()) { "argv must not be empty" }
  ignoreSigpipeOnce

  val (childPid, stdoutFd) = memScoped {
    val stdoutPipe = allocArray<IntVar>(2)
    if (pipe2(stdoutPipe, O_CLOEXEC) != 0) throw ExecException("Failed to create stdout pipe")

    val actions = alloc<klardrop_spawn_file_actions_t>()
    klardrop_spawn_file_actions_init(actions.ptr)

    // Stdin: /dev/null
    klardrop_spawn_file_actions_addopen(actions.ptr, STDIN_FILENO, "/dev/null", O_RDONLY, 0u)

    // Stdout: write to stdoutPipe[1]
    klardrop_spawn_file_actions_adddup2(actions.ptr, stdoutPipe[1], STDOUT_FILENO)
    klardrop_spawn_file_actions_addclose(actions.ptr, stdoutPipe[0])
    klardrop_spawn_file_actions_addclose(actions.ptr, stdoutPipe[1])

    // Stderr: /dev/null
    klardrop_spawn_file_actions_addopen(actions.ptr, STDERR_FILENO, "/dev/null", O_WRONLY, 0u)

    addClosefromOrFallback(actions.ptr, 3, setOf(stdoutPipe[0], stdoutPipe[1]))


    val attr = alloc<klardrop_spawnattr_t>()
    klardrop_spawnattr_init(attr.ptr)
    klardrop_spawnattr_setflags(attr.ptr, KLARDROP_POSIX_SPAWN_SETPGROUP)
    klardrop_spawnattr_setpgroup(attr.ptr, 0)

    val cArgs = allocArray<CPointerVar<ByteVar>>(argv.size + 1)
    for (i in argv.indices) {
      cArgs[i] = argv[i].cstr.ptr
    }
    cArgs[argv.size] = null

    val pid = alloc<pid_tVar>()
    val spawnRet = klardrop_spawnp(pid.ptr, argv[0], actions.ptr, attr.ptr, cArgs, __environ)
    klardrop_spawnattr_destroy(attr.ptr)
    klardrop_spawn_file_actions_destroy(actions.ptr)

    if (spawnRet != 0) {
      close(stdoutPipe[0])
      close(stdoutPipe[1])
      throw ExecException("posix_spawnp failed for '${argv[0]}' (errno $spawnRet)")
    }

    // Parent closes child's write end
    close(stdoutPipe[1])
    val readFd = stdoutPipe[0]
    fcntl(readFd, F_SETFL, O_NONBLOCK)
    Pair(pid.value, readFd)
  }

  val readJob = launch(dispatcher) {
    val bufSize = 512
    memScoped {
      val buffer = allocArray<ByteVar>(bufSize)
      val pfd = alloc<pollfd>()
      pfd.fd = stdoutFd
      pfd.events = (POLLIN or POLLHUP or POLLERR).toShort()
      // Accumulate raw bytes so multibyte UTF-8 sequences are decoded correctly.
      val lineBytes = mutableListOf<Byte>()
      try {
        while (isActive) {
          val pollRet = poll(pfd.ptr, 1u, 200)
          if (pollRet < 0) {
            val err = posix_errno()
            if (err == EINTR) continue
            break
          }
          if (pollRet == 0) {
            continue
          }

          var eof = false
          while (isActive) {
            val n = read(stdoutFd, buffer, bufSize.toULong())
            if (n > 0) {
              val bytes = buffer.readBytes(n.toInt())
              for (b in bytes) {
                when (b.toInt()) {
                  '\n'.code -> {
                    send(lineBytes.toByteArray().decodeToString())
                    lineBytes.clear()
                  }
                  '\r'.code -> { /* strip CR */ }
                  else -> lineBytes.add(b)
                }
              }
            } else if (n == 0L) {
              eof = true
              break
            } else {
              val err = posix_errno()
              if (err == EAGAIN || err == EWOULDBLOCK) {
                break
              } else if (err == EINTR) {
                continue
              } else {
                eof = true
                break
              }
            }
          }
          if (eof) break
        }
        if (lineBytes.isNotEmpty() && isActive) {
          send(lineBytes.toByteArray().decodeToString())
        }
      } finally {
        close(stdoutFd)
        channel.close()
      }
    }
  }

  awaitClose {
    readJob.cancel()
    memScoped {
      val status = alloc<IntVar>()
      // Kill the process group BEFORE reaping the leader so the pgid is still
      // valid. After waitpid returns the leader's pid can be recycled.
      kill(-childPid, SIGTERM)
      val ret = waitpid(childPid, status.ptr, WNOHANG)
      if (ret != childPid) {
        kill(childPid, SIGTERM)
        var reaped = false
        for (i in 0 until 50) {
          val w = waitpid(childPid, status.ptr, WNOHANG)
          if (w == childPid) {
            reaped = true
            break
          }
          usleep(10_000u)
        }
        if (!reaped) {
          kill(-childPid, SIGKILL)
          kill(childPid, SIGKILL)
          waitpid(childPid, status.ptr, 0)
        }
      }
    }
  }
}

