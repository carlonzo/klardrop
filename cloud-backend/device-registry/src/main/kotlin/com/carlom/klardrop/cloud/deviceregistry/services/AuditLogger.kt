package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.database.tables.AuditEventsTable
import mu.KotlinLogging
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

sealed interface AuditEvent {
    val type: String
    val userId: String?
    val deviceId: String?
    val detail: String

    data class SessionExchanged(override val userId: String, val provider: String) : AuditEvent {
        override val type = "session.exchanged"
        override val deviceId: String? = null
        override val detail = "provider=$provider"
    }

    data class PairingCodeIssued(override val userId: String) : AuditEvent {
        override val type = "pairing.issued"
        override val deviceId: String? = null
        override val detail = ""
    }

    data class DeviceEnrolled(override val userId: String, override val deviceId: String) : AuditEvent {
        override val type = "device.enrolled"
        override val detail = ""
    }

    data class DeviceRevoked(override val userId: String, override val deviceId: String) : AuditEvent {
        override val type = "device.revoked"
        override val detail = ""
    }

    data class BrokerTokenRotated(override val userId: String, override val deviceId: String) : AuditEvent {
        override val type = "broker.token_rotated"
        override val detail = ""
    }

    data class BrokerAuthDenied(
        override val userId: String?,
        override val deviceId: String?,
        val reason: String
    ) : AuditEvent {
        override val type = "broker.auth_denied"
        override val detail = "reason=$reason"
    }

    data class BrokerAuthAllowed(
        override val userId: String,
        override val deviceId: String
    ) : AuditEvent {
        override val type = "broker.auth_allowed"
        override val detail = ""
    }
}

interface AuditLogger {
    fun record(event: AuditEvent)
}

object NoopAuditLogger : AuditLogger {
    override fun record(event: AuditEvent) = Unit
}

class LoggingAuditLogger : AuditLogger {
    private val logger = KotlinLogging.logger("audit")
    override fun record(event: AuditEvent) {
        logger.info { "${event.type} userId=${event.userId} deviceId=${event.deviceId} ${event.detail}" }
    }
}

class CompositeAuditLogger(private val delegates: List<AuditLogger>) : AuditLogger {
    override fun record(event: AuditEvent) = delegates.forEach { it.record(event) }
}

class DatabaseAuditLogger : AuditLogger {
    private val logger = KotlinLogging.logger {}

    override fun record(event: AuditEvent) {
        try {
            transaction {
                AuditEventsTable.insert {
                    it[type] = event.type
                    it[userId] = event.userId
                    it[deviceId] = event.deviceId
                    it[detail] = event.detail
                    it[occurredAt] = Instant.now().toEpochMilli()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to persist audit event ${event.type}" }
        }
    }
}
