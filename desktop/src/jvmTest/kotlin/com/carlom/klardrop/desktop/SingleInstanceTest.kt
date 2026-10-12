package com.carlom.klardrop.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceTest {

  /**
   * A data dir whose `instance.sock` actually fits in a Unix domain socket address.
   *
   * macOS caps `sun_path` at 104 bytes and its per-user temp dir (`java.io.tmpdir` there)
   * is already `/var/folders/<2>/<30>/T` — 48 bytes before the directory name — so a
   * `createTempDirectory` under it pushes the socket past the limit. The bind then fails,
   * which SingleInstance.startFocusServer deliberately swallows as best-effort, and every
   * test that needs the primary to hear the second instance times out on a latch that no
   * code path can ever reach. `/tmp` is short on every POSIX host.
   */
  private fun shortDataDir(tag: String): File {
    val dir = Files.createTempDirectory(Path.of("/tmp"), "kdsi-$tag-").toFile()
    dir.deleteOnExit()
    return dir
  }

  @Test
  fun `second instance fails to acquire, sends focus, and primary receives it`() {
    val dataDir = shortDataDir("lock")
    val focusReceived = CountDownLatch(1)

    val primary = SingleInstance.acquire(dataDir)
    assertNotNull(primary, "first acquire must win the lock")
    primary.onFocus = { focusReceived.countDown() }

    val second = SingleInstance.acquire(dataDir)
    assertNull(second, "second acquire must lose the lock and return null")

    assertTrue(
      focusReceived.await(5, TimeUnit.SECONDS),
      "focus callback was not invoked on the primary instance",
    )
    primary.close()
  }

  @Test
  fun `stale socket file from a crashed run is deleted and rebind succeeds`() {
    val dataDir = shortDataDir("stale")
    // Simulate a SIGKILLed previous run: the file lock died with the process but the
    // socket file was left behind.
    File(dataDir, "instance.sock").writeText("garbage")

    val primary = SingleInstance.acquire(dataDir)
    assertNotNull(primary, "acquire must delete a stale socket file and bind successfully")
    primary.close()
  }

  @Test
  fun `missing data dir is created`() {
    val dataDir = File(shortDataDir("mkdir"), "nested/data")

    val primary = SingleInstance.acquire(dataDir)
    assertNotNull(primary)
    assertTrue(dataDir.isDirectory, "acquire must create the data dir if missing")
    primary.close()
  }

  @Test
  fun `second instance sends files to share and primary receives them`() {
    val dataDir = shortDataDir("send")
    val sendReceived = CountDownLatch(1)
    var receivedFiles: List<String>? = null

    val primary = SingleInstance.acquire(dataDir)
    assertNotNull(primary, "first acquire must win the lock")
    primary.onSendFiles = { files ->
      receivedFiles = files
      sendReceived.countDown()
    }

    val filesToSend = listOf("/tmp/test1.png", "/tmp/test2.pdf")
    val second = SingleInstance.acquire(dataDir, filesToSend)
    assertNull(second, "second acquire must lose the lock and return null")

    assertTrue(
      sendReceived.await(5, TimeUnit.SECONDS),
      "onSendFiles callback was not invoked on the primary instance",
    )
    kotlin.test.assertEquals(filesToSend, receivedFiles)
    primary.close()
  }
}
