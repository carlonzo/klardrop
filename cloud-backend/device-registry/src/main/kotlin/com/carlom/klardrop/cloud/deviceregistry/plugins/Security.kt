package com.carlom.klardrop.cloud.deviceregistry.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.carlom.klardrop.cloud.deviceregistry.config.JwtConfig
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureSecurity(jwtConfig: JwtConfig) {
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "klardrop-device-registry"
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtConfig.secret))
                    .withIssuer(jwtConfig.issuer)
                    .withAudience(jwtConfig.audience)
                    .acceptLeeway(JWT_LEEWAY_SECONDS)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.getClaim("user_id").asString()
                if (userId.isNullOrBlank()) null else JWTPrincipal(credential.payload)
            }
        }
    }
}

private const val JWT_LEEWAY_SECONDS = 30L
