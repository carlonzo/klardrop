package com.carlom.klardrop.cloud.deviceregistry.services

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.carlom.klardrop.cloud.deviceregistry.config.Auth0Config
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

interface IdentityProviderVerifier {
    fun verify(idToken: String): VerifiedIdentity
}

data class VerifiedIdentity(
    val providerUserId: String,
    val provider: String = "auth0"
) {
    fun internalUserId(): String {
        val raw = "$provider:$providerUserId"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "usr_${hex.take(20)}"
    }
}

class Auth0IdentityProviderVerifier(auth0Config: Auth0Config) : IdentityProviderVerifier {
    private val jwtVerifier = JWT.require(Algorithm.RSA256(Auth0RsaKeyProvider(auth0Config.domain)))
        .withIssuer(auth0Config.issuer)
        .withAudience(auth0Config.audience)
        .build()

    override fun verify(idToken: String): VerifiedIdentity {
        require(idToken.isNotBlank()) { "idToken is required" }
        val decoded = jwtVerifier.verify(idToken)
        val subject = decoded.subject?.trim().orEmpty()
        require(subject.isNotBlank()) { "Auth0 subject is missing" }
        return VerifiedIdentity(providerUserId = subject)
    }
}

private class Auth0RsaKeyProvider(domain: String) : RSAKeyProvider {
    private val jwkProvider = JwkProviderBuilder(domain)
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

class StubIdentityProviderVerifier : IdentityProviderVerifier {
    override fun verify(idToken: String): VerifiedIdentity {
        require(idToken.startsWith("sub:")) { "Unsupported token format for stub verifier" }
        return VerifiedIdentity(providerUserId = idToken.removePrefix("sub:").trim())
    }
}
