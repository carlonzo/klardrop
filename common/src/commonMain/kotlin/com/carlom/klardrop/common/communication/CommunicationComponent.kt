package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouterImpl
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.SingletonProvider

class CommunicationModule(
  private val coroutines: Coroutines,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val visibleDevices: VisibleDevices,
) {

  private val connectionsPool = SingletonProvider<ConnectionsPool> { ConnectionsPoolImpl() }
  private val incomingMessagesRouter = SingletonProvider<IncomingMessagesRouter> { IncomingMessagesRouterImpl() }
  private val client = SingletonProvider<Client> {
    ClientImpl(
      connectionsPool(),
      coroutines,
      knownDevicesRepository,
      incomingMessagesRouter(),
      localPropertiesRepository
    )
  }
  private val server = SingletonProvider {
    SocketServer(
      localPropertiesRepository,
      connectionsPool(),
      coroutines,
      knownDevicesRepository,
      incomingMessagesRouter()
    )
  }
  private val messenger: Messenger by lazy { MessengerImpl(visibleDevices, connectionsPool(), client(), coroutines) }

  fun connectionsPool() = connectionsPool.get()
  fun incomingMessagesRouter() = incomingMessagesRouter.get()
  fun client() = client.get()
  fun server() = server.get()

  fun messenger() = messenger
}
