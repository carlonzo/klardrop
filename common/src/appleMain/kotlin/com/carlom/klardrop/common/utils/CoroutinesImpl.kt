package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

actual class CoroutinesImpl actual constructor() : Coroutines {

  private val scope by lazy { CoroutineScope(mainDispatcher) }
  override val appScope: CoroutineScope
    get() = scope
  override val ioDispatcher: CoroutineDispatcher
//    get() = Dispatchers.IO when we have coroutines 1.7.1
    get() = cpuDispatcher
  override val mainDispatcher: CoroutineDispatcher
    get() = Dispatchers.Main
  override val cpuDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
}
