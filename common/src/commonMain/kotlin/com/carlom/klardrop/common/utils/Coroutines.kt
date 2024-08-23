package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

interface Coroutines {

  fun newScope(): CoroutineScope
  fun newScope(context: CoroutineContext): CoroutineScope

  val appScope: CoroutineScope

  val ioDispatcher: CoroutineDispatcher
  val mainDispatcher: CoroutineDispatcher
  val cpuDispatcher: CoroutineDispatcher
}

expect class CoroutinesImpl() : Coroutines {
  override fun newScope(): CoroutineScope
  override fun newScope(context: CoroutineContext): CoroutineScope

  override val appScope: CoroutineScope

  override val ioDispatcher: CoroutineDispatcher
  override val mainDispatcher: CoroutineDispatcher
  override val cpuDispatcher: CoroutineDispatcher
}