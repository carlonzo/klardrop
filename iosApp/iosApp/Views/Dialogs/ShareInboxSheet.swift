import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// ShareInboxSheet — host-side device picker shown after another app shares
// files into Klardrop (via the Share Extension → klardrop://share hand-off).
//
// Reuses ShareSheetView for the picker. Send uses DiscoveryAppModel.sendShareFiles
// (not onSendData + immediate onComplete): the sheet stays on Connecting/Sending
// until Completed (short delay) or the user Hide/Close. Same lifetime as
// ShareSheetDismissPolicy in commonMain — do not call onComplete() on Send tap.
//
// QR path is unchanged: session.start, no send-progress chrome.
// ---------------------------------------------------------------------------

struct ShareInboxSheet: View {

    let model: DiscoveryAppModel
    let files: [Filekit_corePlatformFile]
    let onComplete: () -> Void

    @State private var selectedId: String?
    @State private var sessionState: QrShareState? = nil
    @State private var sendProgress: MessengerSendProgress? = nil
    @State private var handoffComplete = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.kdColors) private var kd

    private var session: QrShareSession {
        model.bootstrap.qrShareSession()
    }

    private var currentQrState: QrShareState {
        sessionState ?? session.state.value
    }

    private var isQrActive: Bool {
        if case .idle = currentQrState.sealedType() {
            return false
        }
        return true
    }

    private var devices: [DeviceUi] { model.state.devices }

    private var trusted: [KdShareDevice] {
        devices.filter { $0.isTrustedForShare }.map { $0.asShareDevice }
    }

    private var nearby: [KdShareDevice] {
        devices.filter { !$0.isTrustedForShare }.map { $0.asShareDevice }
    }

    /// Cheap identity for .task(id:) — MessengerSendProgress is not Hashable from Swift.
    private var progressTerminalKey: String {
        guard let sendProgress else { return "none" }
        switch sendProgress.sealedType() {
        case .completed: return "completed"
        case .error: return "error"
        default: return "inflight"
        }
    }

    private var swipeDismissAllowed: Bool {
        ShareSheetDismissPolicy.shared.shouldDismiss(
            trigger: .swipeAway,
            progress: sendProgress,
            handoffComplete: handoffComplete
        )
    }

    var body: some View {
        Group {
            if isQrActive {
                QrShareView(
                    session: session,
                    onDismiss: {
                        if case .serving = currentQrState.sealedType() {
                            dismiss()
                        }
                    }
                )
            } else if sendProgress != nil {
                SendStatusView(
                    progress: sendProgress,
                    onHide: {
                        if ShareSheetDismissPolicy.shared.shouldDismiss(
                            trigger: .userHide,
                            progress: sendProgress,
                            handoffComplete: handoffComplete
                        ) {
                            onComplete()
                        }
                    }
                )
            } else {
                ShareSheetView(
                    trustedDevices: trusted,
                    nearbyDevices: nearby,
                    selectedId: $selectedId,
                    onSend: { share in
                        guard let share,
                              let device = devices.first(where: { $0.deviceId == share.id })
                        else { return }
                        if files.isEmpty {
                            if ShareSheetDismissPolicy.shared.shouldDismiss(
                                trigger: .emptyPayload,
                                progress: nil,
                                handoffComplete: false
                            ) {
                                onComplete()
                            }
                            return
                        }
                        // Connecting first; do not onComplete() here — that was the drop.
                        sendProgress = ShareSheetDismissPolicy.shared.connecting()
                        model.sendShareFiles(device, files: files) { progress in
                            sendProgress = progress
                        }
                        handoffComplete = true
                    },
                    onShareViaQr: {
                        let sharedFiles = files.map { SharedFile(file: $0) }
                        let payload = QrSharePayloadFiles(files: sharedFiles)
                        Task {
                            // swift-export maps Kotlin suspend + CancellationException to throws.
                            // Failures other than cancel are already stored as QrShareState.Failed.
                            _ = try? await session.start(payload: payload)
                        }
                    }
                )
            }
        }
        .interactiveDismissDisabled(!swipeDismissAllowed)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #endif
        .task {
            sessionState = session.state.value
            for await next in session.state.asAsyncSequence() {
                sessionState = next
            }
        }
        .task(id: progressTerminalKey) {
            guard progressTerminalKey == "completed" else { return }
            try? await Task.sleep(nanoseconds: 900_000_000)
            guard !Task.isCancelled else { return }
            if ShareSheetDismissPolicy.shared.shouldDismiss(
                trigger: .completed,
                progress: sendProgress,
                handoffComplete: handoffComplete
            ) {
                onComplete()
            }
        }
    }
}

// MARK: - DeviceUi → KdShareDevice

extension DeviceUi {

    var isTrustedForShare: Bool {
        if case .trusted = trustStatus.sealedType() { return true }
        return false
    }

    var asShareDevice: KdShareDevice {
        KdShareDevice(
            id: deviceId,
            name: deviceName,
            kind: deviceKind,
            isTrusted: isTrustedForShare,
            status: reachabilityStatus
        )
    }
}

// ---------------------------------------------------------------------------
// SendStatusView — transfer status inside a share sheet.
// Mirrors compose-ui/.../components/SendStatus.kt
// ---------------------------------------------------------------------------

private struct SendStatusView: View {

    let progress: MessengerSendProgress?
    let onHide: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(spacing: 0) {
            statusBody
            Spacer().frame(height: KdSpacing.s4)
            Button(action: onHide) {
                Text(isTerminal ? "Close" : "Hide — keeps sending in background")
                    .kdStyle(.body, color: kd.accent)
            }
            .buttonStyle(.plain)
            Spacer().frame(height: KdSpacing.s5)
        }
        .frame(maxWidth: .infinity)
        .padding(KdSpacing.s5)
    }

    @ViewBuilder
    private var statusBody: some View {
        switch progress.map({ $0.sealedType() }) {
        case .inProgress(let p):
            ProgressView(value: Double(p.percentage) / 100.0)
            Spacer().frame(height: KdSpacing.s3)
            Text("Sending… \(p.percentage)%")
                .kdStyle(.body, color: kd.text)
        case .completed:
            Text("Sent ✓")
                .kdStyle(.body, color: kd.text)
        case .error(let e):
            Text("Couldn't send: \(e.message)")
                .kdStyle(.body, color: kd.text)
        case .awaitingRecipient:
            ProgressView()
            Spacer().frame(height: KdSpacing.s3)
            Text("Waiting for the recipient to accept…")
                .kdStyle(.body, color: kd.text)
        default:
            ProgressView()
            Spacer().frame(height: KdSpacing.s3)
            Text("Connecting…")
                .kdStyle(.body, color: kd.text)
        }
    }

    private var isTerminal: Bool {
        guard let progress else { return false }
        switch progress.sealedType() {
        case .completed, .error: return true
        default: return false
        }
    }
}
