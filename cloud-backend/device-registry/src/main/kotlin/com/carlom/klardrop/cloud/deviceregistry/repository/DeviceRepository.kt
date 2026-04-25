package com.carlom.klardrop.cloud.deviceregistry.repository

import com.carlom.klardrop.cloud.deviceregistry.models.Device

interface DeviceRepository {
    fun ensureUser(userId: String)
    fun saveDevice(userId: String, device: Device)
    fun revokeDevice(userId: String, deviceId: String): Boolean
    fun listDevices(userId: String): List<Device>
    fun getDevice(userId: String, deviceId: String): Device?
}
