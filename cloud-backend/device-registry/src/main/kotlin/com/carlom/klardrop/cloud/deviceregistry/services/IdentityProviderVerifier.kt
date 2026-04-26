package com.carlom.klardrop.cloud.deviceregistry.services

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.carlom.klardrop.cloud.deviceregistry.config.OidcConfig
import java.net.URI
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

interface IdentityProviderVerifier {
    fun verify(idToken: String): VerifiedIdentity
}

data class VerifiedIdentity(
    val providerUserId: String,
    val provider: String = "oidc"
) {
    fun internalUserId(): String {
        val raw = "$provider:$providerUserId"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "usr_${hex.take(20)}"
    }
}

/**
 * OIDC token verifier that fetches the issuer's JWKS and validates RS256
 * signatures, issuer, and audience. Works with Auth0, Keycloak, Authentik,
 * Ory Hydra, or any other compliant provider.
 */
class OidcIdentityProviderVerifier(private val oidcConfig: OidcConfig) : IdentityProviderVerifier {
    private val jwtVerifier = JWT.require(Algorithm.RSA256(RemoteJwksKeyProvider(oidcConfig.jwksUrl)))
        .withIssuer(oidcConfig.issuer.trimEnd('/'))
        .withAudience(oidcConfig.audience)
        .acceptLeeway(LEEWAY_SECONDS)
        .build()

    override fun verify(idToken: String): VerifiedIdentity {
        require(idToken.isNotBlank()) { "idToken is required" }
        val decoded = jwtVerifier.verify(idToken)
        val subject = decoded.subject?.trim().orEmpty()
        require(subject.isNotBlank()) { "Identity provider subject is missing" }
        return VerifiedIdentity(providerUserId = subject, provider = oidcConfig.provider)
    }

    companion object {
        private const val LEEWAY_SECONDS = 30L
    }
}

private class RemoteJwksKeyProvider(jwksUrl: String) : RSAKeyProvider {
    private val jwkProvider = JwkProviderBuilder(URI.create(jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    override fun getPublicKeyById(keyId: String?): RSAPublicKey {
        require(!keyId.isNullOrBlank()) { "Missing key id" }
        val key = jwkProvider.get(keyId).publicKey
        return key as RSAPublicKey
    }

    override fun getPrivateKey(): RSAPrivateKey? = null

    override fun getPrivateKeyId(): String? = null
}

/**
 * Local-only verifier for development. Accepts tokens shaped `sub:<user-id>`
 * and uses the bare user-id as the OIDC subject. Never enable in production.
 */
class StubIdentityProviderVerifier : IdentityProviderVerifier {
    override fun verify(idToken: String): VerifiedIdentity {
        require(idToken.startsWith("sub:")) { "Unsupported token format for stub verifier" }
        return VerifiedIdentity(providerUserId = idToken.removePrefix("sub:").trim(), provider = "stub")
    }
}
