package com.carlom.klardrop

import com.carlom.klardrop.chat.DeviceChatViewModel
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop
import com.klardrop.common.initCrashReporter

/**
 * Single Swift-facing entry point that replaces the deleted Compose DiscoveryBridge.
 * Owns the Klardrop instance and exposes the UI controllers to SwiftUI.
 */
class KlardropBootstrap {

    private val applicationInfo = ApplicationInfo()

    val klardrop: Klardrop = Klardrop(
        internalPlatformDependency = InternalPlatformDependencies(applicationInfo)
    )

    init {
        // Started here rather than from iOSApp.swift, which is where Bugsnag used to be
        // started. Keeping SDK startup on the Kotlin side means the Swift entry point
        // has no crash-reporter import at all — one less thing tied to how the Apple
        // targets get their frameworks when CocoaPods goes away.
        initCrashReporter(
            appVersion = applicationInfo.appVersion,
            isProduction = !applicationInfo.isDebug,
        )
        klardrop.init()
    }

    private val uiDependencies: UiDependencies
        get() = UiDependencies(klardrop.commonComponent)

    fun discoveryController(): DiscoveryController = uiDependencies.discoveryController()

    fun updateBannerController(): UpdateBannerController = uiDependencies.updateBannerController()

    fun deviceChatViewModel(deviceId: String): DeviceChatViewModel =
        uiDependencies.deviceChatViewModelFactory(deviceId)
}
