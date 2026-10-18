package com.carlom.klardrop.common.utils

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext

public interface Coroutines {

  public fun newScope(): CoroutineScope
  public fun newScope(context: CoroutineContext): CoroutineScope

  public val appScope: CoroutineScope

  public val ioDispatcher: CoroutineDispatcher
  public val mainDispatcher: CoroutineDispatcher
  public val cpuDispatcher: CoroutineDispatcher
}

internal expect class CoroutinesImpl() : Coroutines {
  override fun newScope(): CoroutineScope
  override fun newScope(context: CoroutineContext): CoroutineScope

  override val appScope: CoroutineScope

  override val ioDispatcher: CoroutineDispatcher
  override val mainDispatcher: CoroutineDispatcher
  override val cpuDispatcher: CoroutineDispatcher
}