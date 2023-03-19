package com.carlom.klardrop.common.utils

internal class SingletonProvider<T>(private val creator: () -> T) {

  private var instance: T? = null

  fun get(): T {
    if (instance == null) {
      instance = creator()
    }
    return instance!!
  }
}