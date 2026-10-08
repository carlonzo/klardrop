@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.posix.spawn.KLARDROP_POSIX_SPAWN_SETPGROUP
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
import com.carlom.klardrop.common.utils.addClosefromOrFallback
import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.concurrent.AtomicInt

/**
 * Linux [TransferAnchor]: holds a systemd sleep-inhibit lock while any transfer is in flight, so
 * suspend/hibernate can't drop the socket mid-stream.
 *
 * The JVM desktop app has no equivalent ([TransferAnchor.None]): a desktop JVM process is never
 * frozen for being idle, but a laptop lid closing or an idle timeout still suspends the whole
 * machine — which drops every socket exactly like macOS idle sleep. The display is left free to
 * turn off, matching the macOS anchor (screen off, transfer keeps going).
 *
 * Mechanism: on the first [begin] we spawn
 * `systemd-inhibit --what=sleep --mode=block sleep infinity` in its own process group; logind
 * holds the lock for as long as that child lives. On the last [end] we SIGTERM the group (which
 * kills both `systemd-inhibit` and the `sleep` it fronts, dropping the lock) and reap the direct
 * child so it never lingers as a zombie. When `systemd-inhibit` can't be started (non-systemd
 * distro, no logind) the spawn fails and the anchor degrades to tracking-only rather than failing
 * the transfer, per the [TransferAnchor] contract.
 *
 * Reference-counted by transfer id and guarded by [spin]: calls arrive on IO scopes and transfers
 * overlap, so the lock is only released when the *last* one finishes. The release kill runs
 * outside the lock — it can block briefly (bounded ~0.5 s grace) waiting for the child to exit.
 */
class LinuxTransferAnchor(
  private val spawnInhibitor: () -> Int? = ::spawnSystemdInhibit,
  private val killInhibitor: (Int) -> Unit = ::killSystemdInhibit,
) : TransferAnchor {

  // ponytail: spinlock, not a pthread mutex — guarded sections are nanosecond field touches
  // entered per-transfer (not per-byte), so a sleeper lock would only add code. Revisit if it
  // ever contends.
  private val spin = AtomicInt(0)

  private inline fun <T> guarded(block: () -> T): T {
    while (!spin.compareAndSet(0, 1)) { /* busy-wait; justified above */ }
    try {
      return block()
    } finally {
      spin.value = 0
    }
  }

  /** Ids of in-flight transfers. Guarded by [spin]. */
  private val active = mutableSetOf<String>()

  /** Pid of the `systemd-inhibit` child, or null while unanchored. Guarded by [spin]. */
  private var inhibitorPid: Int? = null

  override fun begin(transferId: String, label: String, direction: TransferAnchor.Direction) {
    runCatching {
      guarded {
        if (!active.add(transferId)) return@guarded
        if (active.size != 1) return@guarded
        inhibitorPid = spawnInhibitor()
        if (inhibitorPid != null) {
          log("LinuxTransferAnchor", "Transfer started; inhibiting system sleep")
        } else {
          log("LinuxTransferAnchor", "systemd-inhibit unavailable; transfers run unanchored")
        }
      }
    }.onFailure { log("LinuxTransferAnchor", "Anchor begin failed", it) }
  }

  override fun progress(transferId: String, percentage: Int) {
    // Nothing to do: the inhibitor is a plain on/off lever with no progress dimension.
  }

  override fun end(transferId: String) {
    runCatching {
      // Detach the pid under the lock, then kill outside it: the wait below can block briefly.
      val pid = guarded {
        if (!active.remove(transferId)) return@guarded null
        if (active.isNotEmpty()) return@guarded null
        inhibitorPid.also { inhibitorPid = null }
      }
      if (pid != null) {
        killInhibitor(pid)
        log("LinuxTransferAnchor", "All transfers finished; sleep inhibition released")
      }
    }.onFailure { log("LinuxTransferAnchor", "Anchor end failed", it) }
  }
}

/**
 * Spawns `systemd-inhibit --what=sleep --mode=block sleep infinity`, which holds the lock until
 * killed. Returns the child's pid, or null when it can't be started.
 */
internal fun spawnSystemdInhibit(): Int? = memScoped {
  val argv = listOf(
    "systemd-inhibit",
    "--what=sleep",
    "--who=klardrop",
    "--why=File transfer in progress",
    "--mode=block",
    "sleep",
    "infinity",
  )
  val actions = alloc<klardrop_spawn_file_actions_t>()
  klardrop_spawn_file_actions_init(actions.ptr)

  // The inhibitor never speaks: detach stdio and don't let it inherit our sockets/files.
  klardrop_spawn_file_actions_addopen(actions.ptr, STDIN_FILENO, "/dev/null", O_RDONLY, 0u)
  klardrop_spawn_file_actions_addopen(actions.ptr, STDOUT_FILENO, "/dev/null", O_WRONLY, 0u)
  klardrop_spawn_file_actions_addopen(actions.ptr, STDERR_FILENO, "/dev/null", O_WRONLY, 0u)
  addClosefromOrFallback(actions.ptr, 3)

  // Own process group, so the release kill below takes both systemd-inhibit and the sleep child.
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
  val ret = klardrop_spawnp(pid.ptr, argv[0], actions.ptr, attr.ptr, cArgs, __environ)
  klardrop_spawnattr_destroy(attr.ptr)
  klardrop_spawn_file_actions_destroy(actions.ptr)

  if (ret != 0) {
    log("LinuxTransferAnchor", "posix_spawnp(systemd-inhibit) failed (errno $ret)")
    return null
  }
  pid.value
}

/**
 * Drops the lock by SIGTERM-ing the inhibitor's process group, escalating to SIGKILL after a
 * bounded grace wait, and always reaping the direct child so it never lingers as a zombie.
 */
internal fun killSystemdInhibit(pid: Int) {
  memScoped {
    val status = alloc<IntVar>()
    kill(-pid, SIGTERM)
    var reaped = false
    for (i in 0 until 50) {
      val w = waitpid(pid, status.ptr, WNOHANG)
      if (w == pid || (w < 0 && posix_errno() == ECHILD)) {
        reaped = true
        break
      }
      usleep(10_000u)
    }
    if (!reaped) {
      kill(-pid, SIGKILL)
      kill(pid, SIGKILL)
      waitpid(pid, status.ptr, 0)
    }
  }
}
