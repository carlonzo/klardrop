package com.carlom.klardrop.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingleInstanceTest {

  @Test
  fun `second instance fails to acquire, sends focus, and primary receives it`() {
    val dataDir = Files.createTempDirectory("klardrop-single-instance").toFile()
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
    val dataDir = Files.createTempDirectory("klardrop-single-instance-stale").toFile()
    // Simulate a SIGKILLed previous run: the file lock died with the process but the
    // socket file was left behind.
    File(dataDir, "instance.sock").writeText("garbage")

    val primary = SingleInstance.acquire(dataDir)
    assertNotNull(primary, "acquire must delete a stale socket file and bind successfully")
    primary.close()
  }

  @Test
  fun `missing data dir is created`() {
    val dataDir = File(Files.createTempDirectory("klardrop-single-instance-mkdir").toFile(), "nested/data")

    val primary = SingleInstance.acquire(dataDir)
    assertNotNull(primary)
    assertTrue(dataDir.isDirectory, "acquire must create the data dir if missing")
    primary.close()
  }
}
