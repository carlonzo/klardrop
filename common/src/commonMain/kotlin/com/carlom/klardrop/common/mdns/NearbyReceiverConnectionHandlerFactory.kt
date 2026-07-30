package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines

class NearbyReceiverConnectionHandlerFactory(
  private val fileManager: FileManager,
  private val coroutines: Coroutines,
  private val incomingAuthorizer: IncomingAuthorizer,
  private val messageRepository: MessageRepository,
  private val clock: Clock,
  /** Keeps the device awake for the length of an inbound Nearby Share transfer. */
  private val transferAnchor: TransferAnchor = TransferAnchor.None,
) {

  fun get(): NearbyReceiverConnectionHandler {
    return NearbyReceiverConnectionHandler(fileManager, coroutines, incomingAuthorizer, messageRepository, transferAnchor)
  }

}