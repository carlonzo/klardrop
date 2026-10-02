package com.klardrop.common

// "linux" identifies events from the Kotlin/Native daemon (klardrop daemon).
// The JVM desktop binary running on Linux reports "desktop" instead.
internal actual val crashReporterPlatform: String = "linux"
