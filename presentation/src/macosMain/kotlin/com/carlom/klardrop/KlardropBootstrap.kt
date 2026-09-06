package com.carlom.klardrop

import com.carlom.klardrop.chat.DeviceChatViewModel
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.klardrop.common.initCrashReporter
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Single Swift-facing entry point that replaces the deleted Compose DiscoveryBridge.
 * Owns the Klardrop instance and exposes the UI controllers to SwiftUI.
 *
 * macOS twin of the iosMain bootstrap. It must live in the per-platform source set
 * (not appleMain) because it constructs `InternalPlatformDependencies(ApplicationInfo())`,
 * and that one-arg constructor only exists on the per-target actuals — the common
 * `expect class InternalPlatformDependencies` declares no constructor (Android's actual
 * needs a `Context`, so no single shared constructor is possible).
 */
class KlardropBootstrap {

    private val applicationInfo = ApplicationInfo()

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

    private val uiDependencies: UiDependencies
        get() = UiDependencies(klardrop.commonComponent)

    fun discoveryController(): DiscoveryController = uiDependencies.discoveryController()

    fun updateBannerController(): UpdateBannerController = uiDependencies.updateBannerController()

    fun deviceChatViewModel(deviceId: String): DeviceChatViewModel =
        uiDependencies.deviceChatViewModelFactory(deviceId)

    fun qrShareSession() = klardrop.commonComponent.qrShareSession()
}
