package com.carlom.klardrop.cloud.deviceregistry.config

data class AppConfig(
    val server: ServerConfig,
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val sessionJwt: JwtConfig,
    val brokerJwt: BrokerJwtConfig,
    val mqtt: MqttBrokerConfig,
    val oidc: OidcConfig,
    val internalAuth: InternalAuthConfig,
    val environment: AppEnvironment
) {
    companion object {
        fun load(): AppConfig {
            val environment = AppEnvironment.from(env("APP_ENV", "development"))
            val sessionSecret = env("JWT_SECRET", "dev-session-secret-change-me")
            return AppConfig(
                server = ServerConfig(
                    host = env("HOST", "0.0.0.0"),
                    port = env("PORT", "8081").toInt()
                ),
                database = DatabaseConfig(url = env("DATABASE_URL", "")),
                redis = RedisConfig(url = env("REDIS_URL", "")),
                sessionJwt = JwtConfig(
                    secret = sessionSecret,
                    issuer = env("JWT_ISSUER", "klardrop-cloud"),
                    audience = env("JWT_AUDIENCE", "klardrop-device-registry"),
                    ttlSeconds = env("JWT_SESSION_TTL_SECONDS", "86400").toLong()
                ),
                brokerJwt = BrokerJwtConfig(
                    secret = env("BROKER_JWT_SECRET", sessionSecret + "-broker"),
                    issuer = env("BROKER_JWT_ISSUER", env("JWT_ISSUER", "klardrop-cloud")),
                    audience = env("BROKER_JWT_AUDIENCE", "klardrop-mqtt-broker"),
                    ttlSeconds = env("BROKER_JWT_TTL_SECONDS", "900").toLong()
                ),
                mqtt = MqttBrokerConfig(
                    brokerUrl = env("MQTT_BROKER_URL", "ssl://broker.example.com:8883"),
                    topicRoot = env("MQTT_TOPIC_ROOT", "klardrop/v1")
                ),
                oidc = OidcConfig.load(),
                internalAuth = InternalAuthConfig(
                    sharedSecret = env("INTERNAL_SHARED_SECRET", "")
                ),
                environment = environment
            )
        }

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
    }
}

enum class AppEnvironment {
    DEVELOPMENT,
    PRODUCTION;

    val isProduction: Boolean get() = this == PRODUCTION

    companion object {
        fun from(value: String): AppEnvironment = when (value.lowercase()) {
            "production", "prod" -> PRODUCTION
            else -> DEVELOPMENT
        }
    }
}

data class ServerConfig(val host: String, val port: Int)

data class DatabaseConfig(val url: String) {
    val isConfigured: Boolean get() = url.isNotBlank()
}

data class RedisConfig(val url: String) {
    val isConfigured: Boolean get() = url.isNotBlank()
}

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val ttlSeconds: Long
)

data class BrokerJwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val ttlSeconds: Long
)

data class MqttBrokerConfig(val brokerUrl: String, val topicRoot: String) {
    fun userScope(userId: String): String = "$topicRoot/users/$userId"
}

data class InternalAuthConfig(val sharedSecret: String) {
    val isConfigured: Boolean get() = sharedSecret.isNotBlank()
}

/**
 * Generic OIDC identity-provider config. Works with Auth0, Keycloak, Authentik,
 * or any other OIDC issuer that publishes a JWKS.
 *
 * Two configuration styles are supported:
 *
 *  1. **Discovery URL** (preferred for self-hosted Keycloak/Authentik):
 *     set OIDC_ISSUER (e.g. https://idp.example.com/realms/klardrop) and OIDC_AUDIENCE.
 *
 *  2. **Auth0 domain shorthand** (kept for backward compatibility): set
 *     AUTH0_DOMAIN and AUTH0_AUDIENCE; issuer is derived as `https://{domain}/`.
 */
data class OidcConfig(
    val issuer: String,
    val audience: String,
    val jwksUrl: String,
    val provider: String
) {
    val isConfigured: Boolean get() = issuer.isNotBlank() && jwksUrl.isNotBlank()

    companion object {
        fun load(): OidcConfig {
            val explicitIssuer = env("OIDC_ISSUER", "")
            val auth0Domain = env("AUTH0_DOMAIN", "")
            val audience = env("OIDC_AUDIENCE", env("AUTH0_AUDIENCE", "klardrop-device-registry"))

            return when {
                explicitIssuer.isNotBlank() -> {
                    val normalized = if (explicitIssuer.endsWith("/")) explicitIssuer else "$explicitIssuer/"
                    val jwksOverride = env("OIDC_JWKS_URL", "")
                    val jwksUrl = jwksOverride.ifBlank { defaultJwksUrl(normalized) }
                    OidcConfig(normalized, audience, jwksUrl, env("OIDC_PROVIDER", "oidc"))
                }
                auth0Domain.isNotBlank() -> {
                    val issuerUrl = "https://$auth0Domain/"
                    OidcConfig(issuerUrl, audience, "${issuerUrl}.well-known/jwks.json", "auth0")
                }
                else -> OidcConfig("", audience, "", "stub")
            }
        }

        private fun defaultJwksUrl(issuer: String): String {
            // Keycloak: {issuer}/protocol/openid-connect/certs
            // Authentik / generic OIDC: {issuer}/.well-known/jwks.json
            val keycloakLike = issuer.contains("/realms/")
            return if (keycloakLike) "${issuer}protocol/openid-connect/certs"
            else "${issuer}.well-known/jwks.json"
        }

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
    }
}
