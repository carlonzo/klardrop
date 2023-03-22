package com.carlom.klardrop.common.utils

fun log(message: String) {
  println("[Klardrop] $message")
}

fun log(message: String, throwable: Throwable) {
  println("[Klardrop] $message")
  throwable.printStackTrace()
}

fun log(tag: String, message: String) {
  println("[Klardrop]: [$tag]: $message")
}