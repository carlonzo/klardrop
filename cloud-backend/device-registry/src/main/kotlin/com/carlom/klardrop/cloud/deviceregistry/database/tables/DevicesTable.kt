package com.carlom.klardrop.cloud.deviceregistry.database.tables

import org.jetbrains.exposed.sql.Table

object DevicesTable : Table("devices") {
    val id = varchar("id", 64)
    val deviceId = varchar("device_id", 64).uniqueIndex()
    val userId = varchar("user_id", 64).references(UsersTable.id)
    val deviceName = varchar("device_name", 255)
    val platform = varchar("platform", 32)
    val publicKey = text("public_key")
    val mqttClientId = varchar("mqtt_client_id", 255)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
