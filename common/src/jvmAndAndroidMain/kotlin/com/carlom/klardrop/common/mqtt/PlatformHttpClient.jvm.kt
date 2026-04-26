package com.carlom.klardrop.common.mqtt

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

actual fun defaultHttpEngine(): HttpClientEngineFactory<*> = OkHttp
