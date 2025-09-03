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
    System.err.println(message)
  }
}