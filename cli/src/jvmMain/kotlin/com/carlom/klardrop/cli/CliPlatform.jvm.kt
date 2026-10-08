package com.carlom.klardrop.cli

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import io.github.vinceglb.filekit.FileKit
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

internal actual fun cliGetEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

internal actual fun cliSetDataDir(dir: String) {
  System.setProperty(DATA_DIR_PROPERTY, dir)
}

internal actual fun cliInitFileKit(dataDir: String?) {
  if (dataDir != null) {
    val filesDir = File(dataDir)
    val cacheDir = File(dataDir, "cache")
    filesDir.mkdirs()
    cacheDir.mkdirs()
    FileKit.init("klardrop", filesDir, cacheDir)
  } else {
    FileKit.init("klardrop")
  }
}

internal actual fun printErr(message: String) {
  System.err.println(message)
}

internal actual fun cliOnShutdown(action: () -> Unit) {
  Runtime.getRuntime().addShutdownHook(Thread(action))
}

internal actual fun runProcessCaptureStdout(argv: List<String>, timeoutMillis: Long): Pair<Int, String>? {
  return try {
    val process = ProcessBuilder(argv).redirectErrorStream(false).start()
    val output = process.inputStream.bufferedReader().readText()
    val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      return null
    }
    process.exitValue() to output.trim()
  } catch (e: Exception) {
    null
  }
}

private val terminationLatch = CountDownLatch(1)
private val cleanupLatch = CountDownLatch(1)

internal actual fun installTerminationSignalHandler() {
  Runtime.getRuntime().addShutdownHook(Thread {
    terminationLatch.countDown()
    // Block the shutdown-hook thread until the daemon's own cleanup (ControlPlane.stop())
    // completes, otherwise the JVM would exit before it finishes.
    cleanupLatch.await()
  })
}

internal actual suspend fun awaitTerminationRequest() {
  // A blocking await, but on Dispatchers.IO's pool rather than a polling loop: the thread
  // parks (zero CPU) until the shutdown hook counts the latch down.
  withContext(Dispatchers.IO) {
    terminationLatch.await()
  }
}

internal actual fun onCleanupComplete() {
  cleanupLatch.countDown()
}

internal actual fun cliExitProcess(status: Int): Nothing = exitProcess(status)

// Neither of the CLI's targets is Android, so both take the one-argument actual; see the
// expect in CliController.kt for why this cannot be constructed from commonMain.
internal actual fun cliCreateInternalPlatformDependencies(
  applicationInfo: ApplicationInfo,
): InternalPlatformDependencies = InternalPlatformDependencies(applicationInfo)
