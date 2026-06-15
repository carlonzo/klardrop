package com.carlom.klardrop

import com.carlom.klardrop.chat.DeviceChatViewModel
import com.carlom.klardrop.common.di.CommonComponent

// UiDependencies is now a class that holds CommonComponent
class UiDependencies(private val commonComponent: CommonComponent) {

    fun discoveryController(): DiscoveryController {
        // Assuming DiscoveryController now takes CommonComponent directly for simplicity
        // or specific dependencies from it like:
        // return DiscoveryController(commonComponent.visibleDevices(), commonComponent.messenger(), commonComponent.coroutines())
        // Matching the instantiation in KlardropApp.kt for now:
        return DiscoveryController(commonComponent)
    }

    fun updateBannerController(): UpdateBannerController = UpdateBannerController(commonComponent)

    fun deviceChatViewModelFactory(deviceId: String): DeviceChatViewModel {
        return DeviceChatViewModel(
            deviceId = deviceId,
            messageRepository = commonComponent.messageRepository(),
            messenger = commonComponent.messenger(),
            messageReceiver = commonComponent.messageReceiver(),
            coroutines = commonComponent.coroutines(),
            fileManager = commonComponent.fileManager(),
            platformFileSystem = commonComponent.platformFileSystem(),
            clipboardManager = commonComponent.clipboardManager(),
            reachabilitySource = commonComponent.reachability(),
            outbox = commonComponent.messageOutbox(),
        )
    }
}