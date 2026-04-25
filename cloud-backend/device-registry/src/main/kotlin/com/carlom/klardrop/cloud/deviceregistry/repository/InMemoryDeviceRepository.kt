package com.carlom.klardrop.cloud.deviceregistry.repository

import com.carlom.klardrop.cloud.deviceregistry.models.Device
import java.util.concurrent.ConcurrentHashMap

class InMemoryDeviceRepository : DeviceRepository {
    private val userDevices = ConcurrentHashMap<String, MutableMap<String, Device>>()

    override fun ensureUser(userId: String) {
        userDevices.computeIfAbsent(userId) { ConcurrentHashMap() }
    }

    override fun saveDevice(userId: String, device: Device) {
        ensureUser(userId)
        userDevices[userId]!![device.deviceId] = device
    }

    override fun revokeDevice(userId: String, deviceId: String): Boolean {
        return userDevices[userId]?.remove(deviceId) != null
    }

    override fun listDevices(userId: String): List<Device> = userDevices[userId]?.values?.toList().orEmpty()

    override fun getDevice(userId: String, deviceId: String): Device? = userDevices[userId]?.get(deviceId)
}
