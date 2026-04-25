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
                    .build()
            )
            validate { credential ->
                if (credential.payload.getClaim("user_id").asString().isNullOrBlank()) null
                else JWTPrincipal(credential.payload)
            }
        }
    }
}
