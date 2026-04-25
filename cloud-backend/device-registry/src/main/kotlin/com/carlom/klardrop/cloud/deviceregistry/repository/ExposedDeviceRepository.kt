package com.carlom.klardrop.cloud.deviceregistry.repository

import com.carlom.klardrop.cloud.deviceregistry.database.tables.DevicesTable
import com.carlom.klardrop.cloud.deviceregistry.database.tables.UsersTable
import com.carlom.klardrop.cloud.deviceregistry.models.Device
import com.carlom.klardrop.cloud.deviceregistry.models.Platform
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class ExposedDeviceRepository : DeviceRepository {
    override fun ensureUser(userId: String) {
        transaction {
            UsersTable.insertIgnore {
                it[id] = userId
                it[createdAt] = Instant.now().toEpochMilli()
            }
        }
    }

    override fun saveDevice(userId: String, device: Device) {
        transaction {
            DevicesTable.insert {
                it[id] = device.id
                it[deviceId] = device.deviceId
                it[DevicesTable.userId] = userId
                it[deviceName] = device.deviceName
                it[platform] = device.platform.name
                it[publicKey] = device.publicKey
                it[mqttClientId] = device.mqttClientId
                it[createdAt] = device.createdAt.toEpochMilli()
                it[updatedAt] = device.updatedAt.toEpochMilli()
            }
        }
    }

    override fun revokeDevice(userId: String, deviceId: String): Boolean {
        return transaction {
            DevicesTable.update({ (DevicesTable.userId eq userId) and (DevicesTable.deviceId eq deviceId) }) {
                it[revokedAt] = Instant.now().toEpochMilli()
            } > 0
        }
    }

    override fun listDevices(userId: String): List<Device> {
        return transaction {
            DevicesTable
                .selectAll()
                .where { (DevicesTable.userId eq userId) and DevicesTable.revokedAt.isNull() }
                .map { row ->
                    Device(
                        id = row[DevicesTable.id],
                        deviceId = row[DevicesTable.deviceId],
                        deviceName = row[DevicesTable.deviceName],
                        platform = Platform.valueOf(row[DevicesTable.platform]),
                        publicKey = row[DevicesTable.publicKey],
                        mqttClientId = row[DevicesTable.mqttClientId],
                        createdAt = Instant.ofEpochMilli(row[DevicesTable.createdAt]),
                        updatedAt = Instant.ofEpochMilli(row[DevicesTable.updatedAt]),
                        lastSeen = Instant.ofEpochMilli(row[DevicesTable.updatedAt])
                    )
                }
        }
    }

    override fun getDevice(userId: String, deviceId: String): Device? {
        return transaction {
            DevicesTable
                .selectAll()
                .where { (DevicesTable.userId eq userId) and (DevicesTable.deviceId eq deviceId) and DevicesTable.revokedAt.isNull() }
                .limit(1)
                .map { row ->
                    Device(
                        id = row[DevicesTable.id],
                        deviceId = row[DevicesTable.deviceId],
                        deviceName = row[DevicesTable.deviceName],
                        platform = Platform.valueOf(row[DevicesTable.platform]),
                        publicKey = row[DevicesTable.publicKey],
                        mqttClientId = row[DevicesTable.mqttClientId],
                        createdAt = Instant.ofEpochMilli(row[DevicesTable.createdAt]),
                        updatedAt = Instant.ofEpochMilli(row[DevicesTable.updatedAt]),
                        lastSeen = Instant.ofEpochMilli(row[DevicesTable.updatedAt])
                    )
                }
                .firstOrNull()
        }
    }
}

