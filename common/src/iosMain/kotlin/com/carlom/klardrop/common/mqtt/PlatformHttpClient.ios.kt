package com.carlom.klardrop.common.mqtt

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun defaultHttpEngine(): HttpClientEngineFactory<*> = Darwin
