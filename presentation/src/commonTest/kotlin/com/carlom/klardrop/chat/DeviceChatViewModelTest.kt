package com.carlom.klardrop.chat

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.FileTransfer
import com.carlom.klardrop.common.communication.Client
import com.carlom.klardrop.common.communication.ConnectOutcome
import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.database.File_transfers
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.features.ClipboardReaderWriter
import com.carlom.klardrop.common.persistence.ChatMessage
import com.carlom.klardrop.common.persistence.FileTransferStatus
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.PlatformFileSystem
import com.carlom.klardrop.common.utils.ResolvedFileData
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * V8 (docs/connection-review.md, F14): the chat file bubble used to read `transferred_size` from
 * the DB, which is written 0 at insert and never updated, so the bar sat at 0% for the whole
 * transfer then jumped straight to done. These tests pin the fix at the ViewModel level: every
 * live [MessengerSendProgress.InProgress] (send) and [ReceiveMessageStatus.Progress] (receive)
 * must reach [ChatUiState.fileTransferProgress] as it's emitted — not just the terminal status via
 * `.lastOrNull()` — and the fraction must clear on terminal completion/failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceChatViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val testCoroutines: Coroutines = object : Coroutines {
    override val ioDispatcher = dispatcher
    override val mainDispatcher = dispatcher
    override val cpuDispatcher = dispatcher
    override val appScope = CoroutineScope(dispatcher)
    override fun newScope() = CoroutineScope(dispatcher)
    override fun newScope(context: CoroutineContext) = CoroutineScope(dispatcher + context)
  }

  private class FakeMessenger : Messenger {
    val progress = kotlinx.coroutines.flow.MutableSharedFlow<MessengerSendProgress>(extraBufferCapacity = 8)
    var lastSendRequest: SendMessageRequest? = null

    override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {
      lastSendRequest = messageRequest
      return progress
    }

    override fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>> = emptyFlow()
  }

  private class FakeMessageReceiver : MessageReceiver {
    private val _latestUpdates = MutableStateFlow<Map<String, ReceiveMessageUpdate>>(emptyMap())
    override val latestUpdates: StateFlow<Map<String, ReceiveMessageUpdate>> = _latestUpdates

    fun push(deviceId: String, update: ReceiveMessageUpdate) {
      _latestUpdates.update { it + (deviceId to update) }
    }

    override fun onReceiveMessage(deviceId: String) =
      MutableStateFlow(ReceiveMessageUpdate(status = ReceiveMessageStatus.Started))

    override val notifier: Flow<Pair<String, StateFlow<ReceiveMessageUpdate>>> = emptyFlow()
    override val messageReceivedNotifier: Flow<ReceiveMessageUpdate> = emptyFlow()
  }

  /** Records every [connectTo] call so dial-on-open (docs/connection-review.md, F1/F11) can be asserted. */
  private class FakeClient(private val outcome: ConnectOutcome = ConnectOutcome.Connected) : Client {
    val connectToCalls = mutableListOf<String>()

    override suspend fun connectTo(deviceId: String): ConnectOutcome {
      connectToCalls += deviceId
      return outcome
    }
  }

  /** [isAvailable] is configurable per test; every other member is unused by these tests. */
  private class FakeConnectionsPool(private val available: Boolean) : ConnectionsPool {
    override val reachability: StateFlow<Map<String, Reachability>> = MutableStateFlow(emptyMap())
    override suspend fun isAvailable(deviceId: String): Boolean = available
    override suspend fun updateConnection(deviceId: String, connectionMessenger: com.carlom.klardrop.common.communication.ConnectionMessenger) = error("not used by this test")
    override suspend fun getConnection(deviceId: String): com.carlom.klardrop.common.communication.ConnectionMessenger? = error("not used by this test")
    override suspend fun closeAllConnections() = error("not used by this test")
    override suspend fun closeConnection(deviceId: String) = error("not used by this test")
    override fun markProbing(deviceId: String) = error("not used by this test")
    override fun markUnreachable(deviceId: String) = error("not used by this test")
  }

  private class FakeMessageRepository : MessageRepository {
    override suspend fun insertMessage(
      remoteDeviceId: String,
      content: String,
      isSender: Boolean,
      messageType: MessageType,
      fileTransferId: Long?,
      isRead: Boolean,
      mimeType: String,
      messageId: Long?,
      sendStatus: SendStatus,
    ) = Unit

    override suspend fun insertFileTransfer(
      fileName: String,
      filePath: String,
      totalSize: Long,
      status: FileTransferStatus,
      mimeType: String,
    ): Long = 0L

    override suspend fun updateFileTransferStatus(id: Long, status: FileTransferStatus) = Unit
    override suspend fun markStaleInProgressAsFailed() = Unit
    override fun getMessagesForDevice(remoteDeviceId: String, limit: Long): Flow<List<ChatMessage>> = flowOf(emptyList())
    override fun getFileTransferById(id: Long): Flow<File_transfers?> = flowOf(null)
    override suspend fun updateFileTransferFilePath(id: Long, filePath: String) = Unit
    override suspend fun markMessagesAsRead(remoteDeviceId: String) = Unit
    override suspend fun getUnreadCountForDevice(remoteDeviceId: String): Long = 0L
    override fun getAllDevicesWithUnreadCounts(): Flow<Map<String, Long>> = flowOf(emptyMap())
  }

  private class FakeFileManager : FileManager {
    override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer = error("not used by this test")
    override fun getReadStreamFrom(file: PlatformFile): RawSource = error("not used by this test")
    override suspend fun openFile(filePath: String): Boolean = true
    override suspend fun openUrl(url: String): Boolean = true
  }

  private class FakePlatformFileSystem(private val fileSize: Long) : PlatformFileSystem {
    override fun getReadStreamFrom(platformFile: PlatformFile): RawSource = error("not used by this test")
    override fun getWriteStreamTo(path: Path): RawSink = error("not used by this test")
    override fun getResolvedFileData(platformFile: PlatformFile): ResolvedFileData =
      ResolvedFileData(fileName = platformFile.name, mimeType = "application/octet-stream", fileSize = fileSize)
    override suspend fun prepareFileForSending(platformFile: PlatformFile) = error("not used by this test")
    override suspend fun delete(path: Path) = Unit
    override suspend fun moveToStorage(path: Path, mimeType: String): Path? = null
    override fun getTempStoragePath(): Path = Path("/tmp")
    override fun getInternalStoragePath(): Path = Path("/tmp")
    override suspend fun openFile(filePath: String): Boolean = true
    override suspend fun openUrl(url: String): Boolean = true
  }

  private fun buildViewModel(
    messenger: Messenger,
    messageReceiver: MessageReceiver,
    deviceId: String = "dev00001",
    client: Client = FakeClient(),
    connectionsPool: ConnectionsPool = FakeConnectionsPool(available = true),
  ) = DeviceChatViewModel(
    deviceId = deviceId,
    messageRepository = FakeMessageRepository(),
    messenger = messenger,
    messageReceiver = messageReceiver,
    client = client,
    connectionsPool = connectionsPool,
    coroutines = testCoroutines,
    fileManager = FakeFileManager(),
    platformFileSystem = FakePlatformFileSystem(fileSize = 1_000L),
    clipboardManager = ClipboardManager(testCoroutines, ClipboardReaderWriter()),
    reachabilitySource = MutableStateFlow(emptyMap()),
  )

  @Test
  fun sendFileMessage_exposesLiveSendProgress_thenClearsOnCompletion() = runTest(dispatcher) {
    val messenger = FakeMessenger()
    val vm = buildViewModel(messenger, FakeMessageReceiver())

    vm.sendFiles(listOf(PlatformFile(Path("/tmp", "movie.mp4"))))
    advanceUntilIdle()

    messenger.progress.emit(MessengerSendProgress.InProgress(25))
    advanceUntilIdle()
    assertEquals(0.25f, vm.uiState.value.fileTransferProgress)

    messenger.progress.emit(MessengerSendProgress.InProgress(50))
    advanceUntilIdle()
    assertEquals(0.5f, vm.uiState.value.fileTransferProgress)

    messenger.progress.emit(MessengerSendProgress.InProgress(100))
    advanceUntilIdle()
    assertEquals(1.0f, vm.uiState.value.fileTransferProgress)

    messenger.progress.emit(MessengerSendProgress.Completed)
    advanceUntilIdle()
    assertNull(
      vm.uiState.value.fileTransferProgress,
      "fileTransferProgress must clear once the send completes",
    )
  }

  @Test
  fun sendFileMessage_clearsLiveProgress_onError() = runTest(dispatcher) {
    val messenger = FakeMessenger()
    val vm = buildViewModel(messenger, FakeMessageReceiver())

    vm.sendFiles(listOf(PlatformFile(Path("/tmp", "movie.mp4"))))
    advanceUntilIdle()

    messenger.progress.emit(MessengerSendProgress.InProgress(60))
    advanceUntilIdle()
    assertEquals(0.6f, vm.uiState.value.fileTransferProgress)

    messenger.progress.emit(MessengerSendProgress.Error("transport died"))
    advanceUntilIdle()
    assertNull(
      vm.uiState.value.fileTransferProgress,
      "fileTransferProgress must clear once the send fails",
    )
    assertEquals("Failed to send message: transport died", vm.uiState.value.error)
  }

  @Test
  fun incomingFileProgress_updatesUiState_thenClearsOnCompletion() = runTest(dispatcher) {
    val messageReceiver = FakeMessageReceiver()
    val deviceId = "dev00001"
    val vm = buildViewModel(FakeMessenger(), messageReceiver, deviceId)
    advanceUntilIdle() // let the init collector subscribe to latestUpdates

    val header = FileMessage(fileName = "photo.jpg", fileSize = 1_000L, mimeType = "image/jpeg")

    messageReceiver.push(deviceId, ReceiveMessageUpdate(status = ReceiveMessageStatus.Progress(listOf(header to 40))))
    advanceUntilIdle()
    assertEquals(0.4f, vm.uiState.value.fileTransferProgress)

    messageReceiver.push(deviceId, ReceiveMessageUpdate(status = ReceiveMessageStatus.Progress(listOf(header to 90))))
    advanceUntilIdle()
    assertEquals(0.9f, vm.uiState.value.fileTransferProgress)

    messageReceiver.push(deviceId, ReceiveMessageUpdate(status = ReceiveMessageStatus.Completed))
    advanceUntilIdle()
    assertNull(
      vm.uiState.value.fileTransferProgress,
      "fileTransferProgress must clear once the receive completes",
    )
  }

  @Test
  fun incomingFileProgress_forADifferentDevice_isIgnored() = runTest(dispatcher) {
    val messageReceiver = FakeMessageReceiver()
    val vm = buildViewModel(FakeMessenger(), messageReceiver, deviceId = "dev00001")
    advanceUntilIdle()

    val header = FileMessage(fileName = "photo.jpg", fileSize = 1_000L, mimeType = "image/jpeg")
    messageReceiver.push("some-other-device", ReceiveMessageUpdate(status = ReceiveMessageStatus.Progress(listOf(header to 40))))
    advanceUntilIdle()

    assertNull(vm.uiState.value.fileTransferProgress)
  }

  /**
   * Fix 6 (docs/connection-review.md, "dial-on-open", F1/F11): opening the chat screen for a
   * device that isn't currently pooled should nudge exactly one connect attempt, so the user
   * doesn't stare at "Connecting" until the eager connector's cooldown or some other trigger
   * happens to fire.
   */
  @Test
  fun init_unpooledDevice_triggersExactlyOneConnectAttempt() = runTest(dispatcher) {
    val deviceId = "dev00001"
    val client = FakeClient()
    val vm = buildViewModel(
      FakeMessenger(),
      FakeMessageReceiver(),
      deviceId = deviceId,
      client = client,
      connectionsPool = FakeConnectionsPool(available = false),
    )
    advanceUntilIdle()

    assertEquals(listOf(deviceId), client.connectToCalls)
    vm.onDispose()
  }

  @Test
  fun init_pooledDevice_triggersNoConnectAttempt() = runTest(dispatcher) {
    val client = FakeClient()
    val vm = buildViewModel(
      FakeMessenger(),
      FakeMessageReceiver(),
      client = client,
      connectionsPool = FakeConnectionsPool(available = true),
    )
    advanceUntilIdle()

    assertEquals(emptyList(), client.connectToCalls)
    vm.onDispose()
  }
}
