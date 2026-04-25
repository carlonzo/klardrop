package com.carlom.klardrop.cloud.deviceregistry.services

import com.carlom.klardrop.cloud.deviceregistry.models.TransferRoute

class TransferService {
    fun decideRoute(receiverReachableLocally: Boolean): TransferRoute =
        if (receiverReachableLocally) TransferRoute.LOCAL else TransferRoute.CLOUD
}
