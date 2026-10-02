package com.carlom.klardrop.cli

object CliLogging {
  var isDebugMode: Boolean = false

  fun debugLog(tag: String, message: String) {
    if (isDebugMode) {
      println("[$tag]: $message")
    }
  }

  fun info(message: String) {
    println(message)
  }

  fun error(message: String) {
    printErr(message)
  }
}

/** Platform-specific stderr print (System.err on JVM, fputs to stderr on native). */
internal expect fun printErr(message: String)

/**
 * Best-effort hook run right before process exit (Ctrl-C / shutdown), used only for a
 * diagnostic log line. JVM registers a real shutdown hook; native is a no-op since a
 * single-shot log line isn't worth wiring signal handling for non-daemon commands.
 */
internal expect fun cliOnShutdown(action: () -> Unit)