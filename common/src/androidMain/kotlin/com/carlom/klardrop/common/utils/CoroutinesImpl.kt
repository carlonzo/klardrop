package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

actual class CoroutinesImpl actual constructor() : Coroutines {

  private val scope by lazy { CoroutineScope(mainDispatcher) }

  actual override val appScope: CoroutineScope
    get() = scope
  actual override val ioDispatcher: CoroutineDispatcher
    get() = Dispatchers.IO
  actual override val mainDispatcher: CoroutineDispatcher
    get() = Dispatchers.Main
  actual override val cpuDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default
}