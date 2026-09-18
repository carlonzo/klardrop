import SwiftUI
import Observation
import presentation

// ---------------------------------------------------------------------------
// ChatModel — @Observable wrapper over DeviceChatViewModel.
//
// Lifecycle:
//   - Created when a chat screen opens (held as @State in DeviceChatScreen).
//   - start() opens for-await Tasks on the Kotlin StateFlows.
//   - stop() cancels Tasks and calls viewModel.onDispose().
//   - .task { model.start() } preferred driver (auto-cancels on disappear).
//
// StateFlow bridging (swift-export):
//   - messages: StateFlow<List<ChatMessage>> -> iterated via for-await over
//     .asAsyncSequence(), each element arriving as a Swift Array.
//   - uiState/reachability: typed flows -> for-await over .asAsyncSequence().
//   - pendingAuth: Optional StateFlow -> for-await over .asAsyncSequence().
//
// Note: if copyText(_:) below fails to compile, check the generated Swift header —
// `copyText` collides with a Swift keyword context and may surface renamed.
// ---------------------------------------------------------------------------

/// Kotlin default arguments do not survive the Obj-C export, so Swift has to name every field of
/// `ChatUiState` — which means each new field breaks every construction site here. One factory
/// keeps that to a single line. File-scope rather than a static member so it resolves the same
/// inside the `@Observable` macro expansion as it does in the stored-property initialiser.
private func emptyChatUiState() -> ChatUiState {
    ChatUiState(
        error: nil,
        notice: nil,
        fileTransferProgress: nil,
        fileTransferActive: false,
        fileTransferStatusText: nil,
        transferStats: nil
    )
}

@Observable @MainActor
final class ChatModel {

    // MARK: - Observable state

    private(set) var messages: [ChatMessage] = []
    private(set) var uiState: ChatUiState = emptyChatUiState()
    private(set) var pendingAuth: ReceiveMessageUpdate? = nil
    private(set) var reachability: Reachability = ReachabilityUnknown()

    /// Draft text owned here so MessageInputView binds via @Bindable.
    var draft: String = ""

    // MARK: - Kotlin VM handle (created lazily in start(); used by MessageRowView)

    private let deviceId: String
    private let bootstrap: KlardropBootstrap
    private var vmStorage: DeviceChatViewModel?

    /// The Kotlin view-model. Valid after start(); only accessed by the live
    /// screen (post-`.task`) for intents and per-row file-transfer flows.
    var viewModel: DeviceChatViewModel { vmStorage! }

    // MARK: - Tasks

    private var tasks: [Task<Void, Never>] = []

    // MARK: - Init

    // IMPORTANT: init is side-effect free and does NOT create the Kotlin
    // DeviceChatViewModel. SwiftUI rebuilds the navigationDestination / split
    // detail closure on every parent (KlardropNav) re-render, which re-evaluates
    // `ChatModel(...)`. Creating the VM here would run DeviceChatViewModel.init
    // (which launches markMessagesAsRead -> a DB write -> SQLDelight re-emit ->
    // screenStateFlow update -> another KlardropNav re-render) on EVERY render —
    // a self-sustaining feedback loop that pegs the CPU while a chat is open.
    // The VM is created exactly once in start(), driven by the live screen's `.task`.
    init(deviceId: String, bootstrap: KlardropBootstrap) {
        self.deviceId = deviceId
        self.bootstrap = bootstrap
    }

    // MARK: - Lifecycle

    func start() {
        guard vmStorage == nil else { return }
        let vm = bootstrap.deviceChatViewModel(deviceId: deviceId)
        vmStorage = vm

        // Seed from current StateFlow values now that the VM exists (one-time).
        // swift-export exposes .value directly with Swift-native types.
        uiState = vm.uiState.value
        reachability = vm.reachability.value
        pendingAuth = vm.pendingAuth.value
        messages = vm.messages.value

        tasks = [
            // messages: StateFlow<List<ChatMessage>> — Kotlin List maps to Swift Array.
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.messages.asAsyncSequence() {
                    self.messages = next
                }
            },
            // uiState: StateFlow<ChatUiState>
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.uiState.asAsyncSequence() {
                    self.uiState = next
                }
            },
            // reachability: StateFlow<Reachability>
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.reachability.asAsyncSequence() {
                    self.reachability = next
                }
            },
            // pendingAuth: StateFlow<ReceiveMessageUpdate?>
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.pendingAuth.asAsyncSequence() {
                    self.pendingAuth = next
                }
            },
        ]
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks = []
        vmStorage?.onDispose()
        vmStorage = nil
    }

    // MARK: - Intent pass-throughs

    func send() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        viewModel.sendTextMessage(text: trimmed)
        draft = ""
    }

    func sendFiles(_ files: [Filekit_corePlatformFile]) {
        viewModel.sendFiles(files: files)
    }

    func copyText(_ text: String) {
        viewModel.copyText(text: text)
    }

    /// Send the clipboard's current text (attachment chooser "Paste" action).
    /// Reuses the shared VM logic, which also surfaces a "Clipboard is empty" notice.
    func pasteFromClipboard() {
        viewModel.pasteFromClipboard()
    }

    func openFile(_ path: String) {
        viewModel.openFileClicked(filePath: path)
    }

    func openUrl(_ url: String) {
        viewModel.openUrlClicked(url: url)
    }

    func retryFile(_ fileTransferId: Int64) {
        viewModel.retryFileTransfer(failedFileTransferId: fileTransferId)
    }

    func clearError() {
        viewModel.clearError()
    }

    func clearNotice() {
        viewModel.clearNotice()
    }
}
