package com.carlom.klardrop.cloud.deviceregistry

import com.carlom.klardrop.cloud.deviceregistry.config.AppConfig
import com.carlom.klardrop.cloud.deviceregistry.database.DatabaseFactory
import com.carlom.klardrop.cloud.deviceregistry.plugins.*
import com.carlom.klardrop.cloud.deviceregistry.repository.ExposedDeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.repository.InMemoryDeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.routes.deviceRoutes
import com.carlom.klardrop.cloud.deviceregistry.security.TokenService
import com.carlom.klardrop.cloud.deviceregistry.services.ApprovalService
import com.carlom.klardrop.cloud.deviceregistry.services.Auth0IdentityProviderVerifier
import com.carlom.klardrop.cloud.deviceregistry.services.DeviceService
import com.carlom.klardrop.cloud.deviceregistry.services.InMemoryBrokerSessionManager
import com.carlom.klardrop.cloud.deviceregistry.services.RedisService
import com.carlom.klardrop.cloud.deviceregistry.services.TransferService
import com.carlom.klardrop.cloud.deviceregistry.services.StubIdentityProviderVerifier
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() {
    val config = AppConfig.load()

    embeddedServer(
        Netty,
        port = config.server.port,
        host = config.server.host,
        module = { module(config) }
    ).start(wait = true)
}

fun Application.module(config: AppConfig) {
    DatabaseFactory.init(config.database)

    val redisService = RedisService(config.redis)
    val tokenService = TokenService(config.jwt)
    val identityProviderVerifier = if (config.auth0.domain.isBlank()) {
        logger.warn { "AUTH0_DOMAIN is empty. Falling back to stub identity verifier for local development." }
        StubIdentityProviderVerifier()
    } else {
        Auth0IdentityProviderVerifier(config.auth0)
    }

    val deviceRepository = if (DatabaseFactory.connected) ExposedDeviceRepository() else InMemoryDeviceRepository()

    val deviceService = DeviceService(
        redisService = redisService,
        tokenService = tokenService,
        mqttBrokerConfig = config.mqtt,
        identityProviderVerifier = identityProviderVerifier,
        deviceRepository = deviceRepository,
        brokerSessionManager = InMemoryBrokerSessionManager(),
        approvalService = ApprovalService(),
        transferService = TransferService()
    )

    configureSerialization()
    configureMonitoring()
    configureSecurity(config.jwt)
    configureHTTP()
    configureLogging()

    routing {
        route("/api/v1") {
            deviceRoutes(deviceService)
        }
    }

    environment.monitor.subscribe(ApplicationStopping) {
        redisService.close()
        DatabaseFactory.close()
    }

    logger.info { "Device Registry Service started on ${config.server.host}:${config.server.port}" }
}
