package com.carlom.klardrop.cloud.deviceregistry.database.tables

import org.jetbrains.exposed.sql.Table

object AuditEventsTable : Table("audit_events") {
    val id = long("id").autoIncrement()
    val type = varchar("type", 64).index()
    val userId = varchar("user_id", 64).nullable().index()
    val deviceId = varchar("device_id", 64).nullable().index()
    val detail = text("detail")
    val occurredAt = long("occurred_at").index()

    override val primaryKey = PrimaryKey(id)
}
