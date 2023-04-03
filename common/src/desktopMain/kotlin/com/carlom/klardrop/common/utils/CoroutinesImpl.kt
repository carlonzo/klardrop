package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

actual class CoroutinesImpl actual constructor() : Coroutines {

  private val scope by lazy { CoroutineScope(mainDispatcher) }
  override val appScope: CoroutineScope
    get() = scope
  override val ioDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO
  override val mainDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO.limitedParallelism(1)
  override val cpuDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
}