package com.carlom.klardrop.common.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.F_OK
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRWXU
import platform.posix.access
import platform.posix.chmod
import platform.posix.getpid
import platform.posix.rmdir
import platform.posix.stat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The Linux engine resolves its own download directory, where the JVM host gets
 * one FileKit has already created. Without creation, a receiver whose
 * ~/Downloads does not exist yet fails at finalize with ACK_REJECTED and the
 * sender is told the transfer failed for a reason that has nothing to do with
 * the peer.
 */
@OptIn(ExperimentalForeignApi::class)
class EnsureDirectoryLinuxTest {

  private val root: String = "/tmp/klardrop-ensure-directory-${getpid()}"

  private fun exists(path: String): Boolean = access(path, F_OK) == 0

  private fun modeOf(path: String): Int = memScoped {
    val info = alloc<stat>()
    if (stat(path, info.ptr) != 0) fail("stat failed for $path")
    info.st_mode.toInt() and 0xFFF
  }

  /** Depth here is fixed and tiny, so removal is explicit rather than recursive. */
  private fun removeAll(vararg paths: String) {
    for (path in paths) {
      if (exists(path) && rmdir(path) != 0) fail("could not remove the test directory $path")
    }
  }

  /**
   * A private root per test, created up front. The tests share one path because
   * they share one process, so each starts by making sure it exists and each
   * removes it again: a leftover directory would make the next test's
   * "must not exist yet" precondition pass for the wrong reason.
   */
  private fun freshRoot(): String {
    ensureDirectory(root)
    return root
  }

  @Test
  fun createsEveryMissingComponentOfTheHierarchy() {
    val root = freshRoot()
    val nested = "$root/a/b/c"
    removeAll(nested, "$root/a/b", "$root/a", root)
    assertEquals(false, exists(nested), "precondition: the nested path must not exist yet")

    ensureDirectory(nested)

    assertEquals(true, exists(nested), "the directory itself must exist afterwards")
    assertEquals(true, exists("$root/a"), "intermediate components must exist too")
    assertEquals(true, exists("$root/a/b"), "every intermediate component must exist too")
    removeAll(nested, "$root/a/b", "$root/a", root)
  }

  @Test
  fun isIdempotentOnAnExistingDirectory() {
    val root = freshRoot()
    val dir = "$root/twice"
    removeAll(dir, root)
    ensureDirectory(dir)
    // Twice, because the second call is the one the next transfer hits, and it
    // must neither fail nor change anything.
    ensureDirectory(dir)

    assertEquals(true, exists(dir))
    removeAll(dir, root)
  }

  /**
   * The regression that matters most: this must not be `ensureDirectory0700`.
   * A user whose downloads live in a group- or world-readable directory would
   * otherwise find it silently narrowed to 0700 just because Klardrop resolved a
   * path.
   */
  @Test
  fun leavesTheModeOfAnExistingDirectoryAlone() {
    val root = freshRoot()
    val shared = "$root/shared"
    removeAll(shared, root)
    ensureDirectory(shared)
    chmod(shared, (S_IRWXU or S_IRGRP or S_IROTH).toUInt())
    val before = modeOf(shared)
    assertEquals(S_IRWXU.toInt() or S_IRGRP.toInt() or S_IROTH.toInt(), before, "precondition")

    ensureDirectory(shared)

    assertEquals(before, modeOf(shared), "ensureDirectory must not chmod a directory it did not create")
    removeAll(shared, root)
  }

  @Test
  fun createsADirectoryWhoseParentExistsButWhichDoesNot() {
    val root = freshRoot()
    val dir = "$root/sibling"
    removeAll(dir, root)
    ensureDirectory(root)

    ensureDirectory(dir)

    assertEquals(true, exists(dir))
    removeAll(dir, root)
  }
}
