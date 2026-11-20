package com.carlom.klardrop

import androidx.compose.runtime.Composable

/** Returns a QR-scan launcher, or null when scanning isn't available on this platform/device. */
@Composable
internal expect fun rememberQrScanner(onResult: (String) -> Unit): (() -> Unit)?
