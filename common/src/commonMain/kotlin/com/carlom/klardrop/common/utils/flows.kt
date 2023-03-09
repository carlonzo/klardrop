package com.carlom.klardrop.common.utils

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal fun tickerFlow(delayDuration: Duration = 500.milliseconds) = flow {

  while (currentCoroutineContext().isActive) {
    emit(Unit)

    delay(delayDuration)
  }

}