package com.carlom.klardrop.common.features

import com.carlom.klardrop.common.utils.execProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WaylandSocketTest {

  @Test
  fun picksTheSocketNotItsLockFile() {
    assertEquals("wayland-1", waylandSocketName(listOf("bus", "wayland-1.lock", "wayland-1", "pipewire-0")))
  }

  @Test
  fun picksTheLowestNumberedSocket() {
    assertEquals("wayland-2", waylandSocketName(listOf("wayland-10", "wayland-2")))
  }

  @Test
  fun noSocketMeansNoSession() {
    assertNull(waylandSocketName(listOf("bus", "wayland-0.lock")))
  }

  @OptIn(ExperimentalForeignApi::class)
  @Test
  fun adoptedSocketReachesChildProcesses() {
    val savedDisplay = getenv("WAYLAND_DISPLAY")?.toKString()
    val savedRuntime = getenv("XDG_RUNTIME_DIR")?.toKString()
    val dir = execProcess(listOf("mktemp", "-d")).stdoutString.trim()
    try {
      execProcess(listOf("touch", "$dir/wayland-7", "$dir/wayland-7.lock"))
      unsetenv("WAYLAND_DISPLAY")
      setenv("XDG_RUNTIME_DIR", dir, 1)

      ensureWaylandDisplay()

      val seen = execProcess(listOf("sh", "-c", "printf %s \"\$WAYLAND_DISPLAY\"")).stdoutString
      assertEquals("wayland-7", seen)
    } finally {
      if (savedDisplay != null) setenv("WAYLAND_DISPLAY", savedDisplay, 1) else unsetenv("WAYLAND_DISPLAY")
      if (savedRuntime != null) setenv("XDG_RUNTIME_DIR", savedRuntime, 1) else unsetenv("XDG_RUNTIME_DIR")
      execProcess(listOf("rm", "-rf", dir))
    }
  }
}
