package com.carlom.klardrop.common.mqtt

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Real HTTP-backed `DeviceRegistryClient` calling the Ktor service in
 * `cloud-backend/device-registry`.
 *
 * Constructor takes:
 *  - [baseUrl] (e.g. `https://api.klardrop.example.com`).
 *  - [brokerUrl] / [topicRoot] — used to assemble `MqttCredentials` from the
 *    server's broker-token response, since the server doesn't echo those
 *    (they're known to the client from app config / discovery).
 *  - [sessionToken] — async provider returning the current session JWT.
 *    Returns null only on [exchangeSession]; that endpoint takes no auth.
 *  - [httpClient] — defaults to the platform-default engine; tests inject
 *    a `MockEngine`-backed client.
 *
 * Errors:
 *  - 4xx/5xx surface as [DeviceRegistryException] with the parsed `error`
 *    field where available.
 *  - Network failures propagate raw — caller is expected to catch and
 *    translate to a UI-friendly message.
 */
class DeviceRegistryClientHttp(
    private val baseUrl: String,
    private val brokerUrl: String,
    private val topicRoot: String = "klardrop/v1",
    private val sessionToken: suspend () -> String?,
    private val httpClient: HttpClient = createHttpClient()
) : DeviceRegistryClient {

    override suspend fun exchangeSession(idToken: String): SessionExchangeResult {
        val response = httpClient.post {
            url("$baseUrl/api/v1/auth/session/exchange")
            contentType(ContentType.Application.Json)
            setBody(SessionExchangeRequestDto(idToken = idToken))
        }
        if (!response.status.isSuccess()) throw DeviceRegistryException(response.status, response.bodyAsText())
        val body = response.body<SessionExchangeResponseDto>()
        return SessionExchangeResult(userId = body.userId, sessionToken = body.accessToken)
    }

    override suspend fun listDevices(userId: String): List<TrustedDevice> {
        val response = httpClient.get {
            url("$baseUrl/api/v1/users/$userId/devices")
            authedWithSession()
        }
        if (!response.status.isSuccess()) throw DeviceRegistryException(response.status, response.bodyAsText())
        val body = response.body<List<DeviceDto>>()
        return body.map { it.toTrustedDevice() }
    }

    override suspend fun refreshBrokerToken(deviceId: String): MqttCredentials {
        val response = httpClient.post {
            url("$baseUrl/api/v1/devices/me/broker-token")
            contentType(ContentType.Application.Json)
            authedWithSession()
            setBody(BrokerTokenRefreshRequestDto(deviceId = deviceId))
        }
        if (!response.status.isSuccess()) throw DeviceRegistryException(response.status, response.bodyAsText())
        val body = response.body<BrokerTokenRefreshResponseDto>()
        // userId can't be inferred from this endpoint's response shape; the
        // session JWT carries it. The caller (a credential manager) is
        // expected to know the userId from session bootstrap.
        // We embed it via the [forUser] helper rather than guessing here.
        return MqttCredentials(
            brokerUrl = brokerUrl,
            brokerToken = body.brokerToken,
            mqttClientId = "klardrop_<userId>_${body.deviceId}", // placeholder; bound by forUser
            userId = "<unknown>",
            deviceId = body.deviceId,
            topicScope = "$topicRoot/users/<unknown>",
            expiresAtEpochMs = body.brokerTokenExpiresAt,
            ttlSeconds = body.brokerTokenTtlSeconds
        )
    }

    /**
     * Produce credentials for [userId]/[deviceId] by asking the server to
     * mint a fresh broker JWT and stitching the userId-dependent fields in.
     * This is the recommended call site; callers should rarely use
     * [refreshBrokerToken] directly because its result has placeholder
     * userId/topicScope.
     */
    suspend fun refreshCredentials(userId: String, deviceId: String): MqttCredentials {
        val raw = refreshBrokerToken(deviceId)
        return MqttCredentials(
            brokerUrl = raw.brokerUrl,
            brokerToken = raw.brokerToken,
            mqttClientId = "klardrop_${userId}_${raw.deviceId}",
            userId = userId,
            deviceId = raw.deviceId,
            topicScope = "$topicRoot/users/$userId",
            expiresAtEpochMs = raw.expiresAtEpochMs,
            ttlSeconds = raw.ttlSeconds
        )
    }

    private suspend fun io.ktor.client.request.HttpRequestBuilder.authedWithSession() {
        val token = sessionToken()
            ?: error("DeviceRegistryClientHttp: session token is null but the request requires one")
        bearerAuth(token)
    }
}

class DeviceRegistryException(
    val status: HttpStatusCode,
    val rawBody: String
) : RuntimeException("device-registry returned ${status.value}: $rawBody")

// ─── DTOs (mirrors of the device-registry Kotlin models) ─────────────────────

@Serializable
private data class SessionExchangeRequestDto(val idToken: String)

@Serializable
private data class SessionExchangeResponseDto(
    val userId: String,
    val accessToken: String,
    val authProvider: String = "auth0"
)

@Serializable
private data class BrokerTokenRefreshRequestDto(val deviceId: String)

@Serializable
private data class BrokerTokenRefreshResponseDto(
    val deviceId: String,
    val brokerToken: String,
    val brokerTokenExpiresAt: Long,
    val brokerTokenTtlSeconds: Long
)

@Serializable
private data class DeviceDto(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val publicKey: String,        // base64 — see toTrustedDevice()
    val mqttClientId: String,
    val createdAt: String,
    val updatedAt: String,
    val lastSeen: String
) {
    fun toTrustedDevice(): TrustedDevice = TrustedDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        publicKey = decodeBase64(publicKey),
        signatureAlgorithm = SignatureAlgorithm.ED25519,
        enrolledAtMs = parseEpochMs(createdAt)
    )
}

/**
 * Best-effort ISO-8601 → epoch-ms parser. The server serializes `Instant`
 * as `2026-04-26T12:34:56.789Z`; we want a Long.
 *
 * Pulling in kotlinx-datetime just for this would be heavy. The field is
 * metadata (used for display ordering only — not security), so we do a
 * minimal parse and fall back to 0 on any deviation. The server side will
 * eventually grow an `enrolledAtMs: Long` field which renders this lossy
 * path moot.
 */
private fun parseEpochMs(iso8601: String): Long = runCatching {
    val s = iso8601.trim().removeSuffix("Z")
    // Split YYYY-MM-DDTHH:MM:SS[.fff]
    val tIdx = s.indexOf('T').takeIf { it > 0 } ?: return@runCatching 0L
    val date = s.substring(0, tIdx).split('-')
    val time = s.substring(tIdx + 1).split(':')
    if (date.size != 3 || time.size < 3) return@runCatching 0L
    val year = date[0].toInt()
    val month = date[1].toInt()
    val day = date[2].toInt()
    val hour = time[0].toInt()
    val minute = time[1].toInt()
    val secAndFrac = time[2].split('.')
    val sec = secAndFrac[0].toInt()
    val msFrac = if (secAndFrac.size > 1) secAndFrac[1].padEnd(3, '0').take(3).toInt() else 0
    epochMsFromYmdHms(year, month, day, hour, minute, sec) + msFrac
}.getOrElse { 0L }

/** Days-since-1970 calculation per the algorithm in Howard Hinnant's
 *  "date" — works for the proleptic Gregorian year range we care about. */
private fun epochMsFromYmdHms(year: Int, month: Int, day: Int, h: Int, m: Int, s: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400)
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097 + doe - 719468
    return days * 86_400_000L + h * 3_600_000L + m * 60_000L + s * 1_000L
}

/** Minimal base64 decode that works on every KMP target (no java.util.Base64). */
internal fun decodeBase64(s: String): ByteArray {
    val table = IntArray(128) { -1 }
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    for ((i, c) in alphabet.withIndex()) table[c.code] = i
    val cleaned = s.filter { it != '\n' && it != '\r' && it != ' ' }
    val padded = cleaned.trimEnd('=')
    val out = ByteArray((padded.length * 6) / 8)
    var buf = 0
    var bits = 0
    var oi = 0
    for (c in padded) {
        val v = if (c.code < 128) table[c.code] else -1
        require(v >= 0) { "non-base64 character: '$c'" }
        buf = (buf shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[oi++] = ((buf ushr bits) and 0xFF).toByte()
        }
    }
    return out
}

@Suppress("unused")
private val _explicitlyImportedToAvoidUnusedWarnings = ResponseException::class
