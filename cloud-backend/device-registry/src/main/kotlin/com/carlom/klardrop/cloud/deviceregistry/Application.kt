package com.carlom.klardrop.cloud.deviceregistry

import com.carlom.klardrop.cloud.deviceregistry.config.AppConfig
import com.carlom.klardrop.cloud.deviceregistry.database.DatabaseFactory
import com.carlom.klardrop.cloud.deviceregistry.plugins.*
import com.carlom.klardrop.cloud.deviceregistry.repository.ExposedDeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.repository.InMemoryDeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.routes.deviceRoutes
import com.carlom.klardrop.cloud.deviceregistry.routes.internalRoutes
import com.carlom.klardrop.cloud.deviceregistry.security.TokenService
import com.carlom.klardrop.cloud.deviceregistry.services.ApprovalService
import com.carlom.klardrop.cloud.deviceregistry.services.AuditLogger
import com.carlom.klardrop.cloud.deviceregistry.services.BrokerAuthService
import com.carlom.klardrop.cloud.deviceregistry.services.BrokerSessionManager
import com.carlom.klardrop.cloud.deviceregistry.services.CompositeAuditLogger
import com.carlom.klardrop.cloud.deviceregistry.services.DatabaseAuditLogger
import com.carlom.klardrop.cloud.deviceregistry.services.DeviceService
import com.carlom.klardrop.cloud.deviceregistry.services.IdentityProviderVerifier
import com.carlom.klardrop.cloud.deviceregistry.services.InMemoryBrokerSessionManager
import com.carlom.klardrop.cloud.deviceregistry.services.LoggingAuditLogger
import com.carlom.klardrop.cloud.deviceregistry.services.LoggingTrustEventPublisher
import com.carlom.klardrop.cloud.deviceregistry.services.MosquittoBrokerSessionManager
import com.carlom.klardrop.cloud.deviceregistry.services.OidcIdentityProviderVerifier
import com.carlom.klardrop.cloud.deviceregistry.services.PahoTrustEventPublisher
import com.carlom.klardrop.cloud.deviceregistry.services.RedisService
import com.carlom.klardrop.cloud.deviceregistry.services.StubIdentityProviderVerifier
import com.carlom.klardrop.cloud.deviceregistry.services.TransferService
import com.carlom.klardrop.cloud.deviceregistry.services.TrustEventPublisher
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
    enforceProductionInvariants(config)

    DatabaseFactory.init(config.database)

    val redisService = RedisService(config.redis)
    val tokenService = TokenService(config.sessionJwt, config.brokerJwt)
    val identityProviderVerifier = buildIdentityProviderVerifier(config)
    val deviceRepository = if (DatabaseFactory.connected) ExposedDeviceRepository() else InMemoryDeviceRepository()
    val brokerSessionManager: BrokerSessionManager = if (redisService.isConnected) {
        logger.info { "Redis configured; using MosquittoBrokerSessionManager." }
        MosquittoBrokerSessionManager(
            redis = redisService.requireCommands(),
            brokerTokenTtlSeconds = config.brokerJwt.ttlSeconds
        )
    } else {
        logger.warn {
            "Redis not configured; using InMemoryBrokerSessionManager " +
                "(will NOT meet the 30s revocation SLA in multi-replica deployments)."
        }
        InMemoryBrokerSessionManager()
    }

    val auditLogger: AuditLogger = if (DatabaseFactory.connected) {
        CompositeAuditLogger(listOf(LoggingAuditLogger(), DatabaseAuditLogger()))
    } else {
        LoggingAuditLogger()
    }

    val trustEventPublisher: TrustEventPublisher = if (config.brokerService.isConfigured) {
        logger.info { "Broker service credentials present; using PahoTrustEventPublisher." }
        PahoTrustEventPublisher(config.mqtt, config.brokerService)
    } else {
        logger.warn {
            "Broker service credentials missing (MQTT_SERVICE_USERNAME/PASSWORD); " +
                "trust events will be logged only — clients must reconcile via HTTP polling."
        }
        LoggingTrustEventPublisher()
    }

    val deviceService = DeviceService(
        redisService = redisService,
        tokenService = tokenService,
        mqttBrokerConfig = config.mqtt,
        identityProviderVerifier = identityProviderVerifier,
        deviceRepository = deviceRepository,
        brokerSessionManager = brokerSessionManager,
        approvalService = ApprovalService(),
        transferService = TransferService(),
        auditLogger = auditLogger,
        trustEventPublisher = trustEventPublisher
    )

    val brokerAuthService = BrokerAuthService(
        tokenService = tokenService,
        mqttConfig = config.mqtt,
        deviceRepository = deviceRepository,
        brokerSessionManager = brokerSessionManager,
        auditLogger = auditLogger
    )

    configureSerialization()
    configureMonitoring()
    configureSecurity(config.sessionJwt)
    configureHTTP()
    configureLogging()

    routing {
        route("/api/v1") {
            deviceRoutes(deviceService)
            internalRoutes(brokerAuthService, config.internalAuth)
        }
    }

    environment.monitor.subscribe(ApplicationStopping) {
        runCatching { trustEventPublisher.close() }
        redisService.close()
        DatabaseFactory.close()
    }

    logger.info {
        "Device Registry Service started on ${config.server.host}:${config.server.port} " +
            "(env=${config.environment}, oidc=${config.oidc.provider}, " +
            "broker=mosquitto, sessions=${if (redisService.isConnected) "redis" else "in-memory"})"
    }
}

private fun buildIdentityProviderVerifier(config: AppConfig): IdentityProviderVerifier {
    if (!config.oidc.isConfigured) {
        logger.warn { "OIDC issuer not configured; falling back to stub identity verifier for local dev." }
        return StubIdentityProviderVerifier()
    }
    logger.info { "Identity provider configured: issuer=${config.oidc.issuer} provider=${config.oidc.provider}" }
    return OidcIdentityProviderVerifier(config.oidc)
}

/**
 * Hard-fails startup when running in production with development defaults that
 * would silently weaken security or break revocation.
 */
private fun enforceProductionInvariants(config: AppConfig) {
    if (!config.environment.isProduction) return

    val violations = buildList {
        if (config.sessionJwt.secret == "dev-session-secret-change-me") add("JWT_SECRET is the dev default")
        if (config.brokerJwt.secret == config.sessionJwt.secret + "-broker") add("BROKER_JWT_SECRET not set; derived from JWT_SECRET")
        if (config.brokerJwt.secret == config.sessionJwt.secret) add("BROKER_JWT_SECRET equals JWT_SECRET")
        if (!config.database.isConfigured) add("DATABASE_URL not set (would use in-memory device repository)")
        if (!config.redis.isConfigured) add("REDIS_URL not set (pairing codes / revocations would be per-replica)")
        if (!config.oidc.isConfigured) add("OIDC issuer not set (stub verifier accepts arbitrary IDs)")
        if (!config.internalAuth.isConfigured) add("INTERNAL_SHARED_SECRET not set (broker authn webhook would refuse all)")
        if (config.brokerJwt.ttlSeconds > 3600) add("BROKER_JWT_TTL_SECONDS=${config.brokerJwt.ttlSeconds} > 3600; reduce for revocation SLA")
    }
    if (violations.isNotEmpty()) {
        val message = "Refusing to start in production with insecure config:\n" +
            violations.joinToString("\n") { "  - $it" }
        logger.error { message }
        throw IllegalStateException(message)
    }
}
