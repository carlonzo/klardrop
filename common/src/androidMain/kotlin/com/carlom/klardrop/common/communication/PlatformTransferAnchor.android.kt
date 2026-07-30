package com.carlom.klardrop.common.communication

/**
 * Android's real anchor is a foreground service declared in the app manifest, so it lives in the
 * `:android` module and is handed to `Klardrop` at construction time. Nothing useful can be built
 * from `common` alone (a bare wake lock without a foreground component wouldn't stop the process
 * from being frozen), so this stays a no-op.
 */
actual fun platformTransferAnchor(): TransferAnchor = TransferAnchor.None
