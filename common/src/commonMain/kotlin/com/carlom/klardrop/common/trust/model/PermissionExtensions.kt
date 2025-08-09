package com.carlom.klardrop.common.trust.model

/**
 * Extension functions for safe permission handling with default fallbacks
 */

/**
 * Convert a nullable Permission to a safe Permission with minimal default
 */
fun Permission?.toSafePermission(): Permission {
    return this ?: Permission.FILE_SEND // Minimal permission by default
}

/**
 * Convert a nullable set of permissions to a safe set with minimal defaults
 */
fun Set<Permission>?.toSafePermissions(): Set<Permission> {
    return this?.takeIf { it.isNotEmpty() } ?: setOf(Permission.FILE_SEND)
}

/**
 * Get default permissions for a new trusted device with minimal access
 */
fun getDefaultPermissions(): Set<Permission> {
    return setOf(Permission.FILE_SEND) // Start with minimal permission
}

/**
 * Get full permissions for a fully trusted device
 */
fun getFullPermissions(): Set<Permission> {
    return setOf(
        Permission.FILE_SEND,
        Permission.FILE_RECEIVE,
        Permission.CLIPBOARD_SYNC
    )
}

/**
 * Check if a device has clipboard sync permission safely
 */
fun Set<Permission>?.hasClipboardSync(): Boolean {
    return this?.contains(Permission.CLIPBOARD_SYNC) == true
}

/**
 * Check if a device can send files safely  
 */
fun Set<Permission>?.canSendFiles(): Boolean {
    return this?.contains(Permission.FILE_SEND) == true
}

/**
 * Check if a device can receive files safely
 */
fun Set<Permission>?.canReceiveFiles(): Boolean {
    return this?.contains(Permission.FILE_RECEIVE) == true
}

/**
 * Safely add a permission to a set
 */
fun Set<Permission>?.addPermission(permission: Permission): Set<Permission> {
    return (this ?: emptySet()) + permission
}

/**
 * Safely remove a permission from a set
 */
fun Set<Permission>?.removePermission(permission: Permission): Set<Permission> {
    return (this ?: emptySet()) - permission
}