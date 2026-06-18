package com.carlom.klardrop.cli

/**
 * Platform-specific CLI bootstrap (must run before AWT / clipboard init on desktop JVM).
 */
internal expect fun configureCliPlatformRuntime()