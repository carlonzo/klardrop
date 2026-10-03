package com.carlom.klardrop

import com.carlom.klardrop.chat.DeviceChatViewModel
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.klardrop.common.initCrashReporter
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.native.Platform

/**
 * Port the loopback control plane binds, chosen by the host at startup.
 *
 * Defaults to an ephemeral port: the shipped app is a single-instance GUI app with no reason to
 * own a fixed one, and `control.json` publishes whatever port was actually bound. A fixed port
 * only creates a collision with another Klardrop host on the same machine.
 *
 * Overridable for fixtures and for anyone who wants the port pinned:
 *   - `KLARDROP_CONTROL_PORT=8765` binds that port;
 *   - `KLARDROP_CONTROL_PORT=-1` disables the control plane entirely.
 *
 * An unset or unparseable value falls back to the ephemeral default rather than disabling the
 * control plane — losing IPC to a typo in a launch-agent plist would be a worse failure than an
 * unexpected port number.
 */
private const val CONTROL_PORT_ENV = "KLARDROP_CONTROL_PORT"
private const val DEFAULT_CONTROL_PORT = 0

@OptIn(ExperimentalForeignApi::class)
private fun resolveControlPort(): Int =
  getenv(CONTROL_PORT_ENV)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
    ?: DEFAULT_CONTROL_PORT

/**
 * Single Swift-facing entry point that replaces the deleted Compose DiscoveryBridge.
 * Owns the Klardrop instance and exposes the UI controllers to SwiftUI.
 *
 * macOS twin of the iosMain bootstrap. It must live in the per-platform source set
 * (not appleMain) because it constructs `InternalPlatformDependencies(ApplicationInfo())`,
 * and that one-arg constructor only exists on the per-target actuals — the common
 * `expect class InternalPlatformDependencies` declares no constructor (Android's actual
 * needs a `Context`, so no single shared constructor is possible).
 *
 * [controlPort] is the one piece of host wiring this class owns for macOS: it is what makes
 * `ApplicationInfo.controlPort` non-null, which is the switch `ControlPlane.start` looks at. The
 * server itself is *not* started here — `:control-plane` depends on `:presentation`, so this
 * class cannot reference it. MacApp.swift starts it through the `control_plane` framework
 * instead; see the comment there.
 */
class KlardropBootstrap(controlPort: Int = resolveControlPort()) {

    private val applicationInfo = ApplicationInfo(controlPort = controlPort)

    val klardrop: Klardrop = Klardrop(
        internalPlatformDependency = InternalPlatformDependencies(applicationInfo)
    )

    init {
        // Started here rather than from MacApp.swift, which is where Bugsnag used to be
        // started. Keeping SDK startup on the Kotlin side means the Swift entry point
        // has no crash-reporter import at all — one less thing tied to how the Apple
        // targets get their frameworks when CocoaPods goes away.
        // NOT `applicationInfo.isDebug`: that flag comes from the desktop/CLI `--debug`
        // argument and is always false on Apple, so it would let a debug build report.
        // `Platform.isDebugBinary` reflects how this framework was actually compiled,
        // which is the equivalent of the DEBUG-configuration check bugsnag-cocoa used
        // to pick its "development" release stage.
        @OptIn(ExperimentalNativeApi::class)
        initCrashReporter(
            appVersion = applicationInfo.appVersion,
            isProduction = !Platform.isDebugBinary,
        )
        klardrop.init()
    }

    /**
     * Lazily created ONCE per bootstrap, not on every access.
     *
     * DiscoveryController owns a coroutine scope, so two instances means two independent
     * discovery views: the menu bar and the window would list different devices, and the
     * control plane would bind to a controller the UI never renders. `DiscoveryAppModel` and
     * `MacApp.swift` both need this handle — the Swift comment "never call
     * discoveryController() more than once" was a warning about exactly this hazard, and it is
     * now a property the Kotlin side guarantees instead of one the Swift side has to remember.
     */
    private val uiDependencies: UiDependencies by lazy { UiDependencies(klardrop.commonComponent) }

    fun discoveryController(): DiscoveryController = uiDependencies.discoveryController()

    fun updateBannerController(): UpdateBannerController = uiDependencies.updateBannerController()

    fun deviceChatViewModel(deviceId: String): DeviceChatViewModel =
        uiDependencies.deviceChatViewModelFactory(deviceId)

    fun qrShareSession() = klardrop.commonComponent.qrShareSession()
}