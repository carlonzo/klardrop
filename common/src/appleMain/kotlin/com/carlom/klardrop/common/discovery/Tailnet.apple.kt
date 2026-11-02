package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.qrshare.enumerateAppleInterfaces

/** Tailscale's network extension shows up as a `utun` interface holding the device's 100.x address. */
internal actual fun localTailnetIpv4s(): List<String> = tailnetIpv4sOf(enumerateAppleInterfaces())

/**
 * Not asked on Apple targets: iOS can't read another app's VPN state, and the sandboxed macOS app
 * can't run the Tailscale CLI. Remembered peers are all we have.
 */
internal actual suspend fun tailscalePeerIpv4s(): List<String>? = null
