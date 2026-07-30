package com.carlom.klardrop.common.communication

/**
 * Desktop JVM needs no anchor: the process is never killed for being idle, and the JVM has no
 * portable way to block display/system sleep. (The native macOS app gets a real one — see
 * `MacTransferAnchor` — because it can call `NSProcessInfo` directly.)
 */
actual fun platformTransferAnchor(): TransferAnchor = TransferAnchor.None
