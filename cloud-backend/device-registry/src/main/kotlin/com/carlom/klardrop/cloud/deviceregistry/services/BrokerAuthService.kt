package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.config.MqttBrokerConfig
import com.carlom.klardrop.cloud.deviceregistry.models.BrokerAclAccess
import com.carlom.klardrop.cloud.deviceregistry.repository.DeviceRepository
import com.carlom.klardrop.cloud.deviceregistry.security.TokenService

/**
 * Backs the `/v1/internal/broker/auth/{user,acl,superuser}` HTTP webhooks
 * called by Mosquitto (via mosquitto-go-auth's HTTP backend) on every
 * CONNECT, SUBSCRIBE, and PUBLISH (the latter two cached by go-auth for
 * `auth_opt_acl_cache_seconds`).
 *
 * Two endpoints feed into one decision flow:
 *
 *  - `authenticateUser(...)` — called on CONNECT to validate the broker JWT
 *    (presented as the MQTT password), confirm the device hasn't been
 *    revoked, and verify the clientId binding.
 *
 *  - `checkAcl(...)` — called per topic operation. Validates the topic
 *    against a per-(operation, role) ACL scoped to the user's subtree.
 *
 * Splitting them mirrors mosquitto-go-auth's "getuser" / "aclcheck" model
 * and keeps each call cheap. We don't include ACL in the auth response
 * (Mosquitto can't enforce per-user dynamic ACLs from auth alone).
 */
class BrokerAuthService(
    private val tokenService: TokenService,
    private val mqttConfig: MqttBrokerConfig,
    private val deviceRepository: DeviceRepository,
    private val brokerSessionManager: BrokerSessionManager,
    private val auditLogger: AuditLogger = NoopAuditLogger
) {
    /**
     * Stage 1: authenticate the device on CONNECT.
     *
     * `password` is the broker JWT issued by `TokenService.issueBrokerToken`.
     * `clientId` (when present) must equal `klardrop_<userId>_<deviceId>`.
     * `username` is ignored — the JWT is the source of truth for identity.
     */
    fun authenticateUser(password: String, clientId: String?): BrokerAuthDecision {
        val principal = tokenService.verifyBrokerToken(password)
            ?: return deny("invalid broker token", userId = null, deviceId = null)

        if (brokerSessionManager.isRevoked(principal.deviceId)) {
            return deny("device revoked", principal.userId, principal.deviceId)
        }

        val expectedClientId = mqttClientIdFor(principal.userId, principal.deviceId)
        if (!clientId.isNullOrBlank() && clientId != expectedClientId) {
            return deny(
                "clientId mismatch (expected=$expectedClientId, got=$clientId)",
                principal.userId, principal.deviceId
            )
        }

        // Defence in depth — token can outlive the device record by up to TTL.
        val device = deviceRepository.getDevice(principal.userId, principal.deviceId)
            ?: return deny("device not found", principal.userId, principal.deviceId)

        brokerSessionManager.registerSession(device.deviceId, principal.tokenId)
        auditLogger.record(AuditEvent.BrokerAuthAllowed(principal.userId, principal.deviceId))

        return BrokerAuthDecision.Allow(
            userId = principal.userId,
            deviceId = principal.deviceId,
            expiresAtEpochMs = principal.expiresAtEpochMs
        )
    }

    /**
     * Stage 2: per-topic ACL check.
     *
     * Mosquitto-go-auth passes only `(username, clientId, topic, access)`
     * — no JWT — so we trust `clientId` to encode the principal because
     * `authenticateUser` already verified it on CONNECT.
     *
     * We re-check the revoked-set on every ACL call so a revocation that
     * happens mid-session takes effect on the next operation, not just the
     * next reconnect.
     */
    fun checkAcl(clientId: String, topic: String, access: BrokerAclAccess): BrokerAclDecision {
        val principal = ClientIdParser.parse(clientId)
            ?: return BrokerAclDecision.Deny("malformed clientId").also {
                auditLogger.record(AuditEvent.BrokerAuthDenied(null, null, "acl: malformed clientId=$clientId"))
            }

        if (brokerSessionManager.isRevoked(principal.deviceId)) {
            return BrokerAclDecision.Deny("device revoked").also {
                auditLogger.record(
                    AuditEvent.BrokerAuthDenied(principal.userId, principal.deviceId, "acl: revoked")
                )
            }
        }

        val scope = mqttConfig.userScope(principal.userId)
        if (!topic.startsWith("$scope/") && topic != scope) {
            return BrokerAclDecision.Deny("topic outside user scope")
        }

        val allowed = when (access) {
            BrokerAclAccess.SUBSCRIBE -> isSubscribeAllowed(scope, topic)
            BrokerAclAccess.READ -> isSubscribeAllowed(scope, topic)
            BrokerAclAccess.WRITE -> isPublishAllowed(scope, principal.deviceId, topic)
        }
        return if (allowed) BrokerAclDecision.Allow
        else BrokerAclDecision.Deny("acl: $access not allowed for topic=$topic")
    }

    /** What the device is allowed to PUBLISH to. Single-source-of-truth ACL. */
    private fun isPublishAllowed(scope: String, deviceId: String, topic: String): Boolean {
        return matchesAny(
            topic,
            listOf(
                "$scope/presence/$deviceId",
                "$scope/transfer/+/request",
                "$scope/transfer/+/response",
                "$scope/transfer/+/chunks/+",
                "$scope/transfer/+/control",
                "$scope/transfer/+/complete",
                "$scope/trust/ack/$deviceId"
            )
        )
    }

    /** What the device is allowed to SUBSCRIBE to. */
    private fun isSubscribeAllowed(scope: String, topic: String): Boolean {
        return matchesAny(
            topic,
            listOf(
                "$scope/presence/+",
                "$scope/transfer/#",
                "$scope/trust/events"
            )
        )
    }

    /**
     * Match an MQTT topic filter (with `+` single-level and `#` multi-level
     * wildcards) against a concrete topic. Used to validate that a published
     * topic falls within the device's permitted slots.
     */
    private fun matchesAny(topic: String, filters: List<String>): Boolean =
        filters.any { matches(topic, it) }

    private fun matches(topic: String, filter: String): Boolean {
        val topicSegs = topic.split('/')
        val filterSegs = filter.split('/')
        var i = 0
        while (i < filterSegs.size) {
            val f = filterSegs[i]
            if (f == "#") return true                       // matches the rest
            if (i >= topicSegs.size) return false
            if (f != "+" && f != topicSegs[i]) return false
            i++
        }
        return i == topicSegs.size
    }

    private fun deny(reason: String, userId: String?, deviceId: String?): BrokerAuthDecision {
        auditLogger.record(AuditEvent.BrokerAuthDenied(userId, deviceId, reason))
        return BrokerAuthDecision.Deny(reason)
    }

    companion object {
        fun mqttClientIdFor(userId: String, deviceId: String): String =
            MosquittoBrokerSessionManager.mqttClientId(userId, deviceId)
    }
}

sealed class BrokerAuthDecision {
    data class Allow(
        val userId: String,
        val deviceId: String,
        val expiresAtEpochMs: Long
    ) : BrokerAuthDecision()

    data class Deny(val reason: String) : BrokerAuthDecision()
}

sealed class BrokerAclDecision {
    data object Allow : BrokerAclDecision()
    data class Deny(val reason: String) : BrokerAclDecision()
}

/**
 * Decodes the `(userId, deviceId)` principal embedded in an MQTT clientId
 * of the form `klardrop_<userId>_<deviceId>`. Returns null on any deviation
 * — never throws.
 */
internal object ClientIdParser {
    // userIds are `usr_<hex>` (contains one `_`), deviceIds are UUIDs (no `_`).
    // Greedy first capture, then a single trailing `_<deviceId>` segment.
    private val SHAPE = Regex("^klardrop_(.+)_([^_]+)$")

    data class Principal(val userId: String, val deviceId: String)

    fun parse(clientId: String): Principal? {
        val m = SHAPE.matchEntire(clientId) ?: return null
        return Principal(userId = m.groupValues[1], deviceId = m.groupValues[2])
    }
}
