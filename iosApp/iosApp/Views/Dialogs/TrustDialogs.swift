import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// TrustDialogs — dialogs cluster
//
// Contains:
//   - LinkDeviceConfirmDialog   mirrors TrustDialogs.kt LinkDeviceConfirmDialog
//   - ForgetDeviceConfirmDialog mirrors TrustDialogs.kt ForgetDeviceConfirmDialog
//
// Both wrap PairingDialogView and are presented via .sheet(item:) from
// DiscoveryScreen. PairingApprovalSheet (the incoming-pairing variant) lives
// in its own file to keep file sizes manageable.
// ---------------------------------------------------------------------------

// MARK: - LinkDeviceConfirmDialog

/// Confirms promoting a nearby device to trusted ("Yes, it's mine").
/// Presented as a bottom sheet on compact, or a card on regular.
struct LinkDeviceConfirmDialog: View {

    let device: DeviceUi
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    @Environment(\.kdColors) private var kd
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    var body: some View {
        PairingDialogView(
            remoteDeviceName: device.deviceName,
            remoteKind: device.deviceType.toKdDeviceKind(),
            bodyText: "Only do this if it's your own device. Your devices share clipboard, files, and message history with each other automatically.",
            confirmLabel: "Yes, it's mine",
            onCancel: onDismiss,
            onConfirm: onConfirm
        )
        #if os(iOS)
        .padding(sizeClass == .compact ? KdSpacing.s4 : KdSpacing.s2)
        #else
        .padding(KdSpacing.s2)
        #endif
        .sheetPresentation()
    }
}

// MARK: - ForgetDeviceConfirmDialog

/// Destructive confirmation for Forget Device.
/// onConfirm -> model.forgetDevice(device).
struct ForgetDeviceConfirmDialog: View {

    let device: DeviceUi
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    @Environment(\.kdColors) private var kd
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    var body: some View {
        PairingDialogView(
            remoteDeviceName: device.deviceName,
            remoteKind: device.deviceType.toKdDeviceKind(),
            bodyText: "This device will no longer share files, messages, or clipboard with \(device.deviceName). You'll have to pair again to reconnect.",
            confirmLabel: "Forget",
            onCancel: onDismiss,
            onConfirm: onConfirm
        )
        #if os(iOS)
        .padding(sizeClass == .compact ? KdSpacing.s4 : KdSpacing.s2)
        #else
        .padding(KdSpacing.s2)
        #endif
        .sheetPresentation()
    }
}

// MARK: - Presentation modifier helper

private extension View {
    /// Applies native sheet presentation tokens for dialogs.
    func sheetPresentation() -> some View {
        #if os(iOS)
        self
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(KdRadii.sheet)
        #else
        self
            .frame(minWidth: 420, minHeight: 320)
        #endif
    }
}

// MARK: - DeviceType -> KdDeviceKind mapping
// DeviceType is a real Swift enum from the Kotlin bridge.

extension DeviceType {
    /// Maps the Kotlin DeviceType to the iOS design system KdDeviceKind.
    /// KdDeviceKind is owned by the leaf cluster; referenced by name here.
    func toKdDeviceKind() -> KdDeviceKind {
        switch self {
        case .mobile:  return .iphone
        case .desktop: return .mac
        default:       return .unknown
        }
    }
}
