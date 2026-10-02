package com.carlom.klardrop.control

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

/**
 * The macOS App Group that the app and its share extensions already share.
 *
 * Must stay in lockstep with two other places, all of which already hardcode it:
 *   - `iosApp/Shared/ShareInbox.swift` — `ShareInbox.appGroupID`
 *   - `iosApp/iosApp/KlardropMac.entitlements` — `com.apple.security.application.groups`
 *
 * Why a group container and not `~/.cache/klardrop` the way the JVM and Linux hosts do it:
 * the shipped macOS app is sandboxed (`com.apple.security.app-sandbox` in those entitlements),
 * so it cannot write anywhere outside its own container. The App Group container is the one
 * sandboxed location an *unsandboxed* process — the `klardrop` CLI the user runs in Terminal —
 * can also reach, because it is an ordinary directory under the user's own home. That is what
 * makes the two halves of the product agree on where `control.json` lives.
 */
private const val APP_GROUP_ID = "D7T5425WSW.group.com.carlom.Klardrop"

@OptIn(ExperimentalForeignApi::class)
internal actual fun controlFileDirectory(): String? =
  NSFileManager.defaultManager
    .containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID)
    ?.path

internal actual fun writeControlFile(port: Int, token: String, capabilities: List<String>) =
  unixWriteControlFile(resolveControlFilePath(), port, token, capabilities)

internal actual fun deleteControlFile(expectedToken: String?) =
  unixDeleteControlFile(resolveControlFilePath(), expectedToken)

actual fun resolveControlFilePath(): String? = unixResolveControlFilePath()

/**
 * `qr-share` is the one production route this host cannot serve. The full reasoning lives in
 * `QrMatrixRenderer.macos.kt`: the app is sandboxed and cannot execute `qrencode`, and
 * qrcode-kotlin has no macOS artifact.
 */
internal actual val platformUnavailableCapabilities: Set<String> = setOf("qr-share")