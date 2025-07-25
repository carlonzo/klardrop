package com.carlom.klardrop.common.security

import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Random
import com.carlom.klardrop.common.utils.log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Authentication service for Klardrop cloud transfers.
 * Implements JWT-based authentication with device certificates.
 */
class AuthenticationService(
    private val authEndpoint: String,
    private val httpClient: HttpClient,
    private val cryptoProvider: CryptoProvider,
    private val clock: Clock
) {
    private val tokenCache = mutableMapOf<String, CachedToken>()
    private val cacheMutex = Mutex()
    
    /**
     * Authenticates a device and obtains access tokens for MQTT communication
     */
    suspend fun authenticate(deviceCredentials: DeviceCredentials): AuthResult {
        // Check cache first
        val cached = getCachedToken(deviceCredentials.deviceId)
        if (cached != null && !isTokenExpiring(cached)) {
            return cached.toAuthResult()
        }
        
        // Request authentication challenge
        val challenge = requestChallenge(deviceCredentials.deviceId)
        
        // Sign the challenge with device private key
        val signature = cryptoProvider.signData(
            data = challenge.challenge.encodeToByteArray(),
            privateKey = deviceCredentials.deviceKey
        )
        
        // Submit signed challenge
        val response = submitChallenge(
            deviceId = deviceCredentials.deviceId,
            challenge = challenge.challenge,
            signature = signature,
            nonce = challenge.nonce
        )
        
        // Cache the token
        cacheToken(deviceCredentials.deviceId, response)
        
        return response
    }
    
    /**
     * Refreshes an expired or expiring access token
     */
    suspend fun refreshToken(refreshToken: String): AuthResult {
        val response = httpClient.post("$authEndpoint/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(refreshToken))
        }
        
        if (!response.status.isSuccess()) {
            throw AuthenticationException("Token refresh failed: ${response.status}")
        }
        
        return response.body<AuthResult>()
    }
    
    /**
     * Validates a JWT token
     */
    suspend fun validateToken(jwt: String): TokenValidation {
        val response = httpClient.post("$authEndpoint/auth/validate") {
            contentType(ContentType.Application.Json)
            bearerAuth(jwt)
        }
        
        if (!response.status.isSuccess()) {
            return TokenValidation(
                valid = false,
                reason = "Validation failed: ${response.status}"
            )
        }
        
        return response.body<TokenValidation>()
    }
    
    /**
     * Revokes a token (logout)
     */
    suspend fun revokeToken(jwt: String) {
        httpClient.post("$authEndpoint/auth/revoke") {
            contentType(ContentType.Application.Json)
            bearerAuth(jwt)
        }
        
        // Clear from cache
        cacheMutex.withLock {
            tokenCache.entries.removeIf { it.value.authResult.accessToken == jwt }
        }
    }
    
    private suspend fun requestChallenge(deviceId: String): AuthChallenge {
        val response = httpClient.post("$authEndpoint/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(deviceId))
        }
        
        if (!response.status.isSuccess()) {
            throw AuthenticationException("Challenge request failed: ${response.status}")
        }
        
        return response.body<AuthChallenge>()
    }
    
    private suspend fun submitChallenge(
        deviceId: String,
        challenge: String,
        signature: ByteArray,
        nonce: String
    ): AuthResult {
        val response = httpClient.post("$authEndpoint/auth/authenticate") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticationRequest(
                deviceId = deviceId,
                challenge = challenge,
                signature = signature.toBase64(),
                nonce = nonce
            ))
        }
        
        if (!response.status.isSuccess()) {
            throw AuthenticationException("Authentication failed: ${response.status}")
        }
        
        return response.body<AuthResult>()
    }
    
    private suspend fun getCachedToken(deviceId: String): CachedToken? {
        return cacheMutex.withLock {
            tokenCache[deviceId]
        }
    }
    
    private suspend fun cacheToken(deviceId: String, authResult: AuthResult) {
        cacheMutex.withLock {
            tokenCache[deviceId] = CachedToken(
                authResult = authResult,
                cachedAt = clock.currentTimeMillis()
            )
        }
    }
    
    private fun isTokenExpiring(cached: CachedToken): Boolean {
        val now = clock.currentTimeMillis()
        val expiresAt = cached.cachedAt + (cached.authResult.expiresIn * 1000)
        val refreshMargin = 5 * 60 * 1000 // 5 minutes
        return now >= (expiresAt - refreshMargin)
    }
    
    private data class CachedToken(
        val authResult: AuthResult,
        val cachedAt: Long
    ) {
        fun toAuthResult() = authResult
    }
}

/**
 * Device credentials for authentication
 */
data class DeviceCredentials(
    val deviceId: String,
    val deviceKey: ByteArray,  // ECDSA private key
    val deviceCertificate: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceCredentials) return false
        return deviceId == other.deviceId
    }
    
    override fun hashCode(): Int = deviceId.hashCode()
}

/**
 * Authentication result containing tokens and permissions
 */
@Serializable
data class AuthResult(
    val accessToken: String,      // JWT for MQTT access
    val refreshToken: String,     // Long-lived refresh token
    val expiresIn: Long,         // Seconds until expiration
    val mqttClientId: String,    // Unique MQTT client ID
    val mqttTopics: List<String>,// Authorized MQTT topics
    val deviceGroups: List<String> = emptyList()
)

/**
 * Token validation result
 */
@Serializable
data class TokenValidation(
    val valid: Boolean,
    val reason: String? = null,
    val remainingTime: Long? = null,  // Seconds until expiration
    val permissions: List<String>? = null
)

// Internal data classes for API communication
@Serializable
private data class ChallengeRequest(val deviceId: String)

@Serializable
private data class AuthChallenge(
    val challenge: String,
    val nonce: String,
    val expiresIn: Long
)

@Serializable
private data class AuthenticationRequest(
    val deviceId: String,
    val challenge: String,
    val signature: String,
    val nonce: String
)

@Serializable
private data class RefreshTokenRequest(val refreshToken: String)

class AuthenticationException(message: String) : Exception(message)

// Extension function for Base64 encoding
private fun ByteArray.toBase64(): String {
    // This would use platform-specific Base64 encoding
    // Implementation depends on the platform
    return this.joinToString("") { "%02x".format(it) }
}