package com.carlom.klardrop.cloud.deviceregistry.config

data class AppConfig(
    val server: ServerConfig,
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val jwt: JwtConfig,
    val mqtt: MqttBrokerConfig,
    val auth0: Auth0Config
) {
    companion object {
        fun load(): AppConfig {
            val auth0Domain = env("AUTH0_DOMAIN", "")
            return AppConfig(
                server = ServerConfig(
                    host = env("HOST", "0.0.0.0"),
                    port = env("PORT", "8081").toInt()
                ),
                database = DatabaseConfig(url = env("DATABASE_URL", "")),
                redis = RedisConfig(url = env("REDIS_URL", "redis://localhost:6379")),
                jwt = JwtConfig(
                    secret = env("JWT_SECRET", "dev-secret-change-me"),
                    issuer = env("JWT_ISSUER", "klardrop-cloud"),
                    audience = env("JWT_AUDIENCE", "klardrop-device-registry")
                ),
                mqtt = MqttBrokerConfig(
                    brokerUrl = env("MQTT_BROKER_URL", "ssl://broker.example.com:8883"),
                    topicRoot = env("MQTT_TOPIC_ROOT", "klardrop/v1")
                ),
                auth0 = Auth0Config(
                    domain = auth0Domain,
                    audience = env("AUTH0_AUDIENCE", "klardrop-device-registry"),
                    issuer = if (auth0Domain.isBlank()) "" else "https://$auth0Domain/"
                )
            )
        }

        private fun env(key: String, default: String): String =
            System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
    }
}

data class ServerConfig(val host: String, val port: Int)
data class DatabaseConfig(val url: String)
data class RedisConfig(val url: String)
data class JwtConfig(val secret: String, val issuer: String, val audience: String)
data class MqttBrokerConfig(val brokerUrl: String, val topicRoot: String)
data class Auth0Config(val domain: String, val audience: String, val issuer: String)
