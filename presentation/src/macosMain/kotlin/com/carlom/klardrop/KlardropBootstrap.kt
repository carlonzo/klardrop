package com.carlom.klardrop

import com.carlom.klardrop.chat.DeviceChatViewModel
import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.Klardrop

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

    val klardrop: Klardrop = Klardrop(
        internalPlatformDependency = InternalPlatformDependencies(ApplicationInfo())
    )

    init {
        klardrop.init()
    }

    private val uiDependencies: UiDependencies
        get() = UiDependencies(klardrop.commonComponent)

    fun discoveryController(): DiscoveryController = uiDependencies.discoveryController()

    fun updateBannerController(): UpdateBannerController = uiDependencies.updateBannerController()

    fun deviceChatViewModel(deviceId: String): DeviceChatViewModel =
        uiDependencies.deviceChatViewModelFactory(deviceId)
}
