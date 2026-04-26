package com.carlom.klardrop.common.mqtt

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Returns the platform-default HTTP engine. Each platform pulls in a
 * different ktor engine (OkHttp on JVM/Android, Darwin on iOS) — this is
 * how a multiplatform `HttpClient` is built without leaking the engine type
 * up to common code.
 *
 * The returned `HttpClientEngineFactory` is consumed by [createHttpClient]
 * to build a fully-configured `HttpClient` with content negotiation and the
 * shared JSON config in place.
 */
expect fun defaultHttpEngine(): HttpClientEngineFactory<*>

internal val klardropJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Build a `HttpClient` with the platform-default engine and our shared
 * JSON config. Tests pass their own engine (e.g. `MockEngine`) to avoid
 * touching the network.
 */
fun createHttpClient(
    engine: HttpClientEngine? = null,
    extraConfig: HttpClientConfig<*>.() -> Unit = {}
): HttpClient {
    val baseConfig: HttpClientConfig<*>.() -> Unit = {
        install(ContentNegotiation) {
            json(klardropJson)
        }
        extraConfig()
    }
    return if (engine != null) HttpClient(engine, baseConfig)
    else HttpClient(defaultHttpEngine(), baseConfig)
}
