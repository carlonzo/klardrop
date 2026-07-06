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
// StateFlow bridging:
//   - messages: StateFlow<List<ChatMessage>> -> iterated via for-await, each
//     element cast to NSArray then flattened to [ChatMessage] via compactMap.
//     (was [Messages]; the repo now returns ChatMessage.)
//   - uiState/reachability: typed SKIE StateFlow -> for-await, direct cast.
//   - pendingAuth: Optional StateFlow -> for-await.
//
// Note: DeviceChatViewModel.copyText is exposed as doCopyText(text:) in
// Swift (SKIE renames to avoid Swift keyword conflict).
// ---------------------------------------------------------------------------

@Observable @MainActor
final class ChatModel {

    // MARK: - Observable state

    private(set) var messages: [ChatMessage] = []
    private(set) var uiState: ChatUiState = ChatUiState(error: nil, notice: nil, fileTransferProgress: nil)
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
        uiState = vm.uiState.value as? ChatUiState ?? ChatUiState(error: nil, notice: nil, fileTransferProgress: nil)
        reachability = vm.reachability.value as? Reachability ?? ReachabilityUnknown()
        pendingAuth = vm.pendingAuth.value as? ReceiveMessageUpdate
        // Cast to [ChatMessage] — the repo returns ChatMessage, not the raw Messages row.
        if let list = vm.messages.value as? [ChatMessage] {
            messages = list
        } else if let arr = vm.messages.value as? NSArray {
            messages = arr.compactMap { $0 as? ChatMessage }
        }

        tasks = [
            // messages: StateFlow<List<ChatMessage>> — emits Kotlin List, bridged as NSArray
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.messages {
                    if let list = next as? [ChatMessage] {
                        self.messages = list
                    } else if let arr = next as? NSArray {
                        self.messages = arr.compactMap { $0 as? ChatMessage }
                    }
                }
            },
            // uiState: StateFlow<ChatUiState>
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.uiState {
                    if let s = next as? ChatUiState {
                        self.uiState = s
                    }
                }
            },
            // reachability: StateFlow<Reachability>
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.reachability {
                    if let r = next as? Reachability {
                        self.reachability = r
                    }
                }
            },
            // pendingAuth: StateFlow<ReceiveMessageUpdate?> — optional StateFlow
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.pendingAuth {
                    // next is ReceiveMessageUpdate? (already optional via SKIE optional StateFlow)
                    self.pendingAuth = next as? ReceiveMessageUpdate
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
        // SKIE renames copyText -> doCopyText to avoid Swift keyword collision
        viewModel.doCopyText(text: text)
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
