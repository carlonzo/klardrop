package com.carlom.klardrop.common.communication.router

import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.TrustStorage
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.DeviceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingAuthorizerTest {

  private fun makeFlow() = MutableStateFlow(
    ReceiveMessageUpdate(
      device = DeviceInfo("device-1", "Device 1", DeviceType.DESKTOP),
      status = ReceiveMessageStatus.Started
    )
  )

  /** TrustStorage where exactly the listed deviceIds are considered trusted. */
  private fun trustManagerWith(trustedIds: Set<String>): TrustManager {
    val storage = object : TrustStorage {
      override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {}
      override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {}
      override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? =
        if (deviceId in trustedIds) byteArrayOf(0x1) else null
      override suspend fun getECDSAKey(deviceId: String): ByteArray? = null
      override suspend fun getAllTrustedDevices(): Map<String, ByteArray> = emptyMap()
      override suspend fun removeTrustedDevice(deviceId: String) {}
      override suspend fun clearAllTrustedDevices() {}
      override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {}
      override suspend fun getDevicePrivateKey(): ByteArray? = null
      override suspend fun storeDevicePublicKey(publicKey: ByteArray) {}
      override suspend fun getDevicePublicKey(): ByteArray? = null
      override suspend fun deleteDevicePrivateKey() {}
    }
    val localPropsRepo = object : com.carlom.klardrop.common.persistence.LocalPropertiesRepository {
      override val properties = kotlinx.coroutines.flow.flowOf(
        com.carlom.klardrop.common.persistence.KlardropProperties("self-id", "Self")
      )
      override suspend fun getProperty() =
        com.carlom.klardrop.common.persistence.KlardropProperties("self-id", "Self")
      override suspend fun save(properties: com.carlom.klardrop.common.persistence.KlardropProperties) {}
      override suspend fun saveCustomDeviceName(customDeviceName: String?) {}
      override suspend fun saveBackgroundDiscoveryEnabled(enabled: Boolean) {}
    }
    return TrustManager(
      crypto = TrustCrypto(),
      storage = storage,
      clock = Clock(),
      currentDeviceProvider = com.carlom.klardrop.common.discovery.CurrentDeviceProvider(localPropsRepo)
    )
  }

  @Test
  fun trustedDeviceAutoAcceptsFile() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(setOf("trusted-id")))
    val flow = makeFlow()

    val accepted = authorizer.authorize(
      fromDeviceId = "trusted-id",
      kind = IncomingAuthorizer.TransferKind.FILE,
      headers = listOf(TextMessage(text = "x")),
      receiveFlow = flow
    )

    assertTrue(accepted)
    // No prompt was emitted — flow stays in Started.
    assertEquals(ReceiveMessageStatus.Started, flow.value.status)
  }

  @Test
  fun untrustedFilePromptsAndAcceptsWhenUserAccepts() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(emptySet()))
    val flow = makeFlow()

    val deferred = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.FILE,
        headers = listOf(TextMessage(text = "preview")),
        receiveFlow = flow
      )
    }

    // Authorizer should have emitted a PendingAuthorization status with an accept callback.
    val status = flow.value.status
    assertTrue(status is ReceiveMessageStatus.PendingAuthorization, "expected pending, got $status")
    status.acceptTransfer(true)

    assertTrue(deferred.await())
  }

  @Test
  fun untrustedFileRejectionMarksFlowFailed() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(emptySet()))
    val flow = makeFlow()

    val deferred = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.FILE,
        headers = listOf(TextMessage(text = "preview")),
        receiveFlow = flow
      )
    }

    val pending = flow.value.status as ReceiveMessageStatus.PendingAuthorization
    pending.acceptTransfer(false)

    assertFalse(deferred.await())
    assertTrue(flow.value.status is ReceiveMessageStatus.Failed)
  }

  @Test
  fun untrustedTextOnlyPromptsOnFirstContact() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(emptySet()))

    // First text from this device — must prompt and require user accept.
    val firstFlow = makeFlow()
    val firstAuth = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.TEXT,
        headers = listOf(TextMessage(text = "hi")),
        receiveFlow = firstFlow
      )
    }
    val firstStatus = firstFlow.value.status as ReceiveMessageStatus.PendingAuthorization
    firstStatus.acceptTransfer(true)
    assertTrue(firstAuth.await())

    // Second text from same device — should auto-accept without prompting.
    val secondFlow = makeFlow()
    val secondAccepted = authorizer.authorize(
      fromDeviceId = "untrusted-id",
      kind = IncomingAuthorizer.TransferKind.TEXT,
      headers = listOf(TextMessage(text = "again")),
      receiveFlow = secondFlow
    )
    assertTrue(secondAccepted)
    assertEquals(ReceiveMessageStatus.Started, secondFlow.value.status, "no prompt should have been emitted")
  }

  @Test
  fun untrustedFilesAlwaysPromptEvenAfterFirstContactAccepted() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(emptySet()))

    // Accept the first interaction (a text) so the device is in firstContactAccepted.
    val firstFlow = makeFlow()
    val firstAuth = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.TEXT,
        headers = listOf(TextMessage(text = "hi")),
        receiveFlow = firstFlow
      )
    }
    (firstFlow.value.status as ReceiveMessageStatus.PendingAuthorization).acceptTransfer(true)
    firstAuth.await()

    // Now a file from the same device should still prompt — files always prompt for untrusted.
    val fileFlow = makeFlow()
    val fileAuth = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.FILE,
        headers = listOf(TextMessage(text = "f.bin")),
        receiveFlow = fileFlow
      )
    }
    val fileStatus = fileFlow.value.status
    assertTrue(
      fileStatus is ReceiveMessageStatus.PendingAuthorization,
      "files must always prompt even after first contact, got $fileStatus"
    )
    fileStatus.acceptTransfer(true)
    assertTrue(fileAuth.await())
  }

  @Test
  fun untrustedTransferInvokesNotifyAwaitingUserBeforeBlockingOnDecision() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(emptySet()))
    val flow = makeFlow()
    var notified = false

    val deferred = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.FILE,
        headers = listOf(TextMessage(text = "preview")),
        receiveFlow = flow,
        notifyAwaitingUser = { notified = true },
      )
    }

    // notifyAwaitingUser must fire by the time we're parked on the decision — that's the
    // signal the sender uses to extend its short ACK timeout, so it has to happen before
    // we wait for the user.
    assertTrue(notified, "notifyAwaitingUser should be called once the prompt is shown")

    val pending = flow.value.status as ReceiveMessageStatus.PendingAuthorization
    pending.acceptTransfer(true)
    assertTrue(deferred.await())
  }

  @Test
  fun trustedDeviceDoesNotInvokeNotifyAwaitingUser() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(setOf("trusted-id")))
    var notified = false

    val accepted = authorizer.authorize(
      fromDeviceId = "trusted-id",
      kind = IncomingAuthorizer.TransferKind.FILE,
      headers = listOf(TextMessage(text = "x")),
      receiveFlow = makeFlow(),
      notifyAwaitingUser = { notified = true },
    )

    assertTrue(accepted)
    assertFalse(notified, "trusted devices auto-accept; no awaiting-user signal needed")
  }

  @Test
  fun rejectedTextDoesNotMarkFirstContactAccepted() = runTest(UnconfinedTestDispatcher()) {
    val authorizer = IncomingAuthorizer(trustManagerWith(emptySet()))

    // Reject the first text.
    val rejectFlow = makeFlow()
    val rejectAuth = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.TEXT,
        headers = listOf(TextMessage(text = "hi")),
        receiveFlow = rejectFlow
      )
    }
    (rejectFlow.value.status as ReceiveMessageStatus.PendingAuthorization).acceptTransfer(false)
    assertFalse(rejectAuth.await())

    // Next text from the same device must still prompt — rejection doesn't accidentally
    // grant first-contact status.
    val nextFlow = makeFlow()
    val nextAuth = async {
      authorizer.authorize(
        fromDeviceId = "untrusted-id",
        kind = IncomingAuthorizer.TransferKind.TEXT,
        headers = listOf(TextMessage(text = "hi again")),
        receiveFlow = nextFlow
      )
    }
    val nextStatus = nextFlow.value.status
    assertTrue(nextStatus is ReceiveMessageStatus.PendingAuthorization)
    nextStatus.acceptTransfer(true)
    assertTrue(nextAuth.await())
  }
}
