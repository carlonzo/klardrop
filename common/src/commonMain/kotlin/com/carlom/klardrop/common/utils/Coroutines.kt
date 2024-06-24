package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

interface Coroutines {

  val appScope: CoroutineScope

  val ioDispatcher: CoroutineDispatcher
  val mainDispatcher: CoroutineDispatcher
  val cpuDispatcher: CoroutineDispatcher
}

expect class CoroutinesImpl() : Coroutines {
  override val appScope: CoroutineScope

  override val ioDispatcher: CoroutineDispatcher
  override val mainDispatcher: CoroutineDispatcher
  override val cpuDispatcher: CoroutineDispatcher
}