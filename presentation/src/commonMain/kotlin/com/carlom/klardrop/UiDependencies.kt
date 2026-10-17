package com.carlom.klardrop

import com.carlom.klardrop.chat.DeviceChatViewModel
import com.carlom.klardrop.common.di.CommonComponent

// UiDependencies is now a class that holds CommonComponent
public class UiDependencies(private val commonComponent: CommonComponent) {

    private val discoveryControllerInstance by lazy { DiscoveryController(commonComponent) }

    public fun discoveryController(): DiscoveryController = discoveryControllerInstance

    public fun updateBannerController(): UpdateBannerController = UpdateBannerController(commonComponent)

    public fun deviceChatViewModelFactory(deviceId: String): DeviceChatViewModel {
        return DeviceChatViewModel(
            deviceId = deviceId,
            messageRepository = commonComponent.messageRepository(),
            messenger = commonComponent.messenger(),
            messageReceiver = commonComponent.messageReceiver(),
            client = commonComponent.client(),
            connectionsPool = commonComponent.connectionsPool(),
            coroutines = commonComponent.coroutines(),
            fileManager = commonComponent.fileManager(),
            platformFileSystem = commonComponent.platformFileSystem(),
            clipboardManager = commonComponent.clipboardManager(),
            reachabilitySource = commonComponent.reachability(),
        )
    }
}