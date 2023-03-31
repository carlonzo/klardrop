package com.carlom.klardrop.common.communication.di

import com.carlom.klardrop.common.communication.Client
import com.carlom.klardrop.common.communication.ClientImpl
import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerImpl
import com.carlom.klardrop.common.communication.Server
import com.carlom.klardrop.common.communication.message.EnvelopeHandlers
import com.carlom.klardrop.common.communication.message.EnvelopeHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.FileEnvelopeHandler
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouter
import com.carlom.klardrop.common.communication.router.IncomingMessagesRouterImpl
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.SingletonProvider
import okio.Path.Companion.toPath

class CommunicationModule(
  private val coroutines: Coroutines,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val visibleDevices: VisibleDevices,
) {

  private val envelopeHandlers = SingletonProvider<EnvelopeHandlers> {
    EnvelopeHandlersImpl(
      mapOf(
        MessageType.FILE to FileEnvelopeHandler("".toPath()),

        )
    )
  }

  private val connectionsPool = SingletonProvider<ConnectionsPool> { ConnectionsPoolImpl() }
  private val incomingMessagesRouter = SingletonProvider<IncomingMessagesRouter> { IncomingMessagesRouterImpl(envelopeHandlers.get()) }
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
    Server(
      localPropertiesRepository,
      connectionsPool(),
      coroutines,
      knownDevicesRepository,
      incomingMessagesRouter()
    )
  }
  private val messenger: Messenger by lazy {
    MessengerImpl(
      visibleDevices,
      connectionsPool(),
      client(),
      coroutines,
      envelopeHandlers.get()
    )
  }

  fun connectionsPool() = connectionsPool.get()
  fun incomingMessagesRouter() = incomingMessagesRouter.get()
  fun client() = client.get()
  fun server() = server.get()

  fun messenger() = messenger
}
