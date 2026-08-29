package com.carlom.klardrop.common.discovery

/**
 * No-op on Android: there is no portable in-process listener probe here yet.
 * Returning true = "assume alive" so the watchdog never logs a false WARNING.
 */
actual fun verifyAdvertisedPortAlive(port: Int): Boolean = true
