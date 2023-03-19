package com.carlom.klardrop.common.utils

fun log(message: String) {
  println("Klardrop: $message")
}

fun log(message: String, throwable: Throwable) {
  println("Klardrop: $message")
  throwable.printStackTrace()
}