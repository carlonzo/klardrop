package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.utils.Coroutines

class NearbyReceiverConnectionHandlerFactory(
  private val internalPlatformDependencies: InternalPlatformDependencies,
  private val fileManager: FileManager,
  private val coroutines: Coroutines
) {

  fun get(): NearbyReceiverConnectionHandler {
    return NearbyReceiverConnectionHandler(internalPlatformDependencies, fileManager, coroutines)
  }

}