import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// IncomingBannerStackView
// Vertical stack above device list for system notifications + incoming transfers.
// Mirrors: compose-ui/.../IncomingBannerStack.kt + received_card.kt
//
// Filtering logic (same as Compose):
//   - PendingAuthorization with a real Text/File header = show transfer card
//   - ConnectionInfo = show transfer card (Wi-Fi handoff)
//   - UiNotification.PeerRevokedTrust = system notification card
// ---------------------------------------------------------------------------

struct IncomingBannerStackView: View {
    let state: DiscoveryScreenState
    let model: DiscoveryAppModel

    @Environment(\.kdColors) private var kd

    // MARK: - Filtered updates

    private var filteredUpdates: [(key: Int32, value: ReceiveMessageUpdate)] {
        state.receivingMessages.compactMap { (id, update) -> (key: Int32, value: ReceiveMessageUpdate)? in
            // receivingMessages is NSDictionary<KotlinInt,ReceiveMessageUpdate>.
            // KotlinInt bridges as NSNumber in Swift — use int32Value.
            guard let nsNum = id as? NSNumber else { return nil }
            let id32 = nsNum.int32Value

            let hasRealHeader = update.messages.contains { msg in
                msg is TextMessage || msg is FileMessage
            }
            let hasConnectionInfo = update.messages.contains { $0 is ConnectionInfoMessage }

            let isPendingWithHeader: Bool = {
                switch onEnum(of: update.status) {
                case .pendingAuthorization:
                    return hasRealHeader
                default:
                    return false
                }
            }()

            guard isPendingWithHeader || hasConnectionInfo else { return nil }
            return (key: id32, value: update)
        }
        .sorted { $0.key < $1.key }
    }

    private var filteredNotifications: [UiNotification] {
        state.notifications.compactMap { n -> UiNotification? in
            switch onEnum(of: n) {
            case .peerRevokedTrust:
                return n
            }
        }
    }

    // MARK: - Body

    var body: some View {
        if filteredUpdates.isEmpty && filteredNotifications.isEmpty {
            EmptyView()
        } else {
            VStack(spacing: KdSpacing.s2) {
                // System notifications above transfer cards
                ForEach(filteredNotifications, id: \.id) { notification in
                    systemNotificationView(notification)
                }

                // Transfer / connection-info cards
                ForEach(filteredUpdates, id: \.key) { entry in
                    ReceiveCardView(
                        id: entry.key,
                        update: entry.value,
                        model: model
                    )
                }
            }
            .padding(.horizontal, KdSpacing.s3)
            .padding(.vertical, KdSpacing.s2)
        }
    }

    @ViewBuilder
    private func systemNotificationView(_ notification: UiNotification) -> some View {
        switch onEnum(of: notification) {
        case .peerRevokedTrust(let p):
            SystemNotificationCardView(
                title: p.deviceName,
                bodyText: "removed this device. You\u{2019}re no longer paired.",
                primaryAction: "Pair",
                onPrimary: { model.onNotificationPair(p.id) },
                secondaryAction: "Dismiss",
                onSecondary: { model.onNotificationDismissed(p.id) }
            )
        }
    }
}

// MARK: - ReceiveCardView (single incoming transfer / connection-info card)

private struct ReceiveCardView: View {
    let id: Int32
    let update: ReceiveMessageUpdate
    let model: DiscoveryAppModel

    @State private var visible = true
    @Environment(\.kdColors) private var kd

    var body: some View {
        if visible {
            cardContent
                .transition(.asymmetric(
                    insertion: .identity,
                    removal: .opacity.combined(with: .scale(scale: 0.95))
                ))
                .onReceive(statusChangeTimer) { _ in
                    checkAutoHide()
                }
        }
    }

    @ViewBuilder
    private var cardContent: some View {
        switch onEnum(of: update.status) {
        case .pendingAuthorization(let pending):
            let firstFile = update.messages.compactMap { $0 as? FileMessage }.first
            let isText = firstFile == nil && update.messages.contains { $0 is TextMessage }

            IncomingTransferCardView(
                senderName: update.device?.name ?? "Unknown device",
                fileName: firstFile?.fileName,
                fileSize: nil,
                subtitle: isText ? "wants to send you a message" : "wants to send you a file",
                onAccept: {
                    // acceptTransfer is (PresentationBoolean *) -> Void — Bool auto-bridges.
                    pending.acceptTransfer(true)
                    model.onReceivedCardClicked(update)
                },
                onDecline: {
                    pending.acceptTransfer(false)
                    model.onCardDismissed(id)
                    withAnimation(.easeOut(duration: 0.2)) { visible = false }
                }
            )
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) {
                    pending.acceptTransfer(false)
                    model.onCardDismissed(id)
                    withAnimation(.easeOut(duration: 0.2)) { visible = false }
                } label: {
                    Label("Decline", systemImage: "xmark")
                }
            }

        default:
            // ConnectionInfo or other non-pending
            let firstFile = update.messages.compactMap { $0 as? FileMessage }.first
            let firstConn = update.messages.compactMap { $0 as? ConnectionInfoMessage }.first
            let displayName = firstFile?.fileName ?? firstConn.map { "Wi-Fi: \($0.ssid)" }

            IncomingTransferCardView(
                senderName: update.device?.name ?? "Unknown device",
                fileName: displayName,
                fileSize: nil,
                subtitle: "wants to connect",
                onAccept: {
                    if let conn = firstConn {
                        model.onConnectionInfoAccepted(conn)
                    }
                    model.onCardDismissed(id)
                    withAnimation(.easeOut(duration: 0.2)) { visible = false }
                },
                onDecline: {
                    model.onCardDismissed(id)
                    withAnimation(.easeOut(duration: 0.2)) { visible = false }
                }
            )
        }
    }

    // Poll-free status check: check whenever the view re-renders; use a timer
    // only to catch Completed/Failed which arrive as state updates from Kotlin.
    private let statusChangeTimer = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    private func checkAutoHide() {
        switch onEnum(of: update.status) {
        case .completed, .failed:
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                withAnimation(.easeOut(duration: 0.2)) { visible = false }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    model.onCardDismissed(id)
                }
            }
        default:
            break
        }
    }
}
