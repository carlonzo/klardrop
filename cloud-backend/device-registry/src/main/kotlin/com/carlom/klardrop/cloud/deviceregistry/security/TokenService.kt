package com.carlom.klardrop.cloud.deviceregistry.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.carlom.klardrop.cloud.deviceregistry.config.JwtConfig
import java.util.Date

class TokenService(private val jwtConfig: JwtConfig) {
    private val algorithm = Algorithm.HMAC256(jwtConfig.secret)

    fun issueSessionToken(userId: String): String =
        JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.audience)
            .withClaim("user_id", userId)
            .withExpiresAt(Date(System.currentTimeMillis() + SESSION_TTL_MS))
            .sign(algorithm)

    fun issueBrokerToken(userId: String, deviceId: String): String =
        JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience("klardrop-mqtt-broker")
            .withClaim("user_id", userId)
            .withClaim("device_id", deviceId)
            .withExpiresAt(Date(System.currentTimeMillis() + BROKER_TTL_MS))
            .sign(algorithm)

    companion object {
        private const val SESSION_TTL_MS = 1000L * 60 * 60 * 24
        private const val BROKER_TTL_MS = 1000L * 60 * 60 * 24
    }
}
