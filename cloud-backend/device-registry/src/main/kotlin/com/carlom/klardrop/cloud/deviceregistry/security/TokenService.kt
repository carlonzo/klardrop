package com.carlom.klardrop.cloud.deviceregistry.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import com.carlom.klardrop.cloud.deviceregistry.config.BrokerJwtConfig
import com.carlom.klardrop.cloud.deviceregistry.config.JwtConfig
import java.util.Date
import java.util.UUID

/**
 * Issues two distinct token kinds with **separate signing keys**:
 *
 *  - **Session JWT** — long-lived (default 24h), used to authenticate the
 *    device against the device-registry HTTP API.
 *  - **Broker JWT** — short-lived (default 15m), used by the device to
 *    authenticate against the MQTT broker; the broker calls back to verify it.
 *
 * Splitting keys means a leaked session token can't be presented to the broker
 * and vice versa, even if the audience check is bypassed.
 */
class TokenService(
    private val sessionConfig: JwtConfig,
    private val brokerConfig: BrokerJwtConfig
) {
    private val sessionAlgorithm = Algorithm.HMAC256(sessionConfig.secret)
    private val brokerAlgorithm = Algorithm.HMAC256(brokerConfig.secret)

    private val brokerVerifier = JWT.require(brokerAlgorithm)
        .withIssuer(brokerConfig.issuer)
        .withAudience(brokerConfig.audience)
        .acceptLeeway(LEEWAY_SECONDS)
        .build()

    fun issueSessionToken(userId: String): String =
        JWT.create()
            .withIssuer(sessionConfig.issuer)
            .withAudience(sessionConfig.audience)
            .withClaim("user_id", userId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date())
            .withExpiresAt(Date(System.currentTimeMillis() + sessionConfig.ttlSeconds * 1000L))
            .sign(sessionAlgorithm)

    fun issueBrokerToken(userId: String, deviceId: String): BrokerToken {
        val now = System.currentTimeMillis()
        val expiresAt = now + brokerConfig.ttlSeconds * 1000L
        val token = JWT.create()
            .withIssuer(brokerConfig.issuer)
            .withAudience(brokerConfig.audience)
            .withSubject(deviceId)
            .withClaim("user_id", userId)
            .withClaim("device_id", deviceId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(expiresAt))
            .sign(brokerAlgorithm)
        return BrokerToken(token = token, expiresAtEpochMs = expiresAt, ttlSeconds = brokerConfig.ttlSeconds)
    }

    /**
     * Verify a broker JWT presented at MQTT CONNECT. Returns the resolved
     * principal claims, or null if the token is invalid/expired/wrong-audience.
     */
    fun verifyBrokerToken(token: String): BrokerPrincipal? {
        if (token.isBlank()) return null
        return try {
            val decoded: DecodedJWT = brokerVerifier.verify(token)
            val userId = decoded.getClaim("user_id").asString().orEmpty()
            val deviceId = decoded.getClaim("device_id").asString().orEmpty()
            if (userId.isBlank() || deviceId.isBlank()) return null
            BrokerPrincipal(
                userId = userId,
                deviceId = deviceId,
                tokenId = decoded.id.orEmpty(),
                expiresAtEpochMs = decoded.expiresAt?.time ?: 0L
            )
        } catch (_: JWTVerificationException) {
            null
        }
    }

    companion object {
        private const val LEEWAY_SECONDS = 30L
    }
}

data class BrokerToken(
    val token: String,
    val expiresAtEpochMs: Long,
    val ttlSeconds: Long
)

data class BrokerPrincipal(
    val userId: String,
    val deviceId: String,
    val tokenId: String,
    val expiresAtEpochMs: Long
)
