import SwiftUI
import presentation
#if os(iOS)
import VisionKit
import AVFoundation
#endif

// ---------------------------------------------------------------------------
// AddDevicePickerSheet — dialogs cluster
//
// Bottom sheet (compact) / centered card (regular) listing nearby untrusted
// candidate devices as DeviceRowViews ("Tap to pair" / "Pairing…"). Empty
// hint shown when no candidates are nearby. Close button dismisses. Below the
// list, a manual-add section (code/IP field, optional QR scan, own code + QR).
//
// Also provides AddDevicePlaceholderSurface — the empty-trusted-devices CTA
// card shown in DiscoveryScreen's "Your devices" section. Both live here so
// the add-device flow is entirely self-contained.
//
// Mirrors AddDevicePickerSheet.kt + AddDevicePlaceholderSurface.
// ---------------------------------------------------------------------------

struct AddDevicePickerSheet: View {

    let candidates: [DeviceUi]
    let onDismiss: () -> Void
    let onPick: (DeviceUi) -> Void
    let manualAddInfo: () async -> ManualAddInfo?
    let onAddManually: (String) async -> ManualAddResult?

    @Environment(\.kdColors) private var kd

    var body: some View {
        AddDevicePickerContent(
            candidates: candidates,
            onDismiss: onDismiss,
            onPick: onPick,
            manualAddInfo: manualAddInfo,
            onAddManually: onAddManually
        )
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #else
        .frame(minWidth: 420, minHeight: 520)
        .background(kd.bg1)
        #endif
    }
}

// MARK: - Sheet content

private struct AddDevicePickerContent: View {

    let candidates: [DeviceUi]
    let onDismiss: () -> Void
    let onPick: (DeviceUi) -> Void
    let manualAddInfo: () async -> ManualAddInfo?
    let onAddManually: (String) async -> ManualAddResult?

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            VStack(alignment: .leading, spacing: KdSpacing.s2) {
                Text("Add a device")
                    .kdStyle(.title, color: kd.text)
                    .padding(.horizontal, KdSpacing.s5)
                    .padding(.top, KdSpacing.s4)

                Text("Pick a nearby device to pair with. Trusted devices auto-share clipboard, Wi-Fi logins and notifications.")
                    .kdStyle(.caption, color: kd.text2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, KdSpacing.s5)
                    .padding(.bottom, KdSpacing.s3)
            }

            Divider()
                .background(kd.divider)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // Candidate list
                    if candidates.isEmpty {
                        emptyState
                    } else {
                        candidateList
                    }

                    Divider()
                        .background(kd.divider)
                        .padding(.vertical, KdSpacing.s3)

                    ManualAddSection(
                        manualAddInfo: manualAddInfo,
                        onAddManually: onAddManually
                    )
                }
            }

            // Close button
            HStack {
                Spacer()
                Button(action: onDismiss) {
                    Text("Close")
                        .kdStyle(.body, color: kd.accent)
                }
                .padding(.trailing, KdSpacing.s4)
                .padding(.bottom, KdSpacing.s4)
            }
        }
    }

    private var emptyState: some View {
        Text("No nearby devices right now. Make sure Klardrop is open on the device you want to pair, and that both are on the same Wi-Fi. If they still don't show up, add by code below.")
            .kdStyle(.body, color: kd.text2)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(KdSpacing.s5)
    }

    private var candidateList: some View {
        LazyVStack(spacing: 0) {
            ForEach(candidates, id: \.deviceId) { device in
                candidateRow(device)
            }
        }
    }

    @ViewBuilder
    private func candidateRow(_ device: DeviceUi) -> some View {
        let isPairing: Bool = {
            switch device.trustStatus.sealedType() {
            case .pairing: return true
            default: return false
            }
        }()

        DeviceRowView(
            name: device.deviceName,
            subText: isPairing ? "Pairing\u{2026}" : "Tap to pair",
            kind: device.deviceType.toKdDeviceKind(),
            avatarStyle: .neutral,
            rowState: isPairing ? .pairing : .idle,
            onTap: {
                if !isPairing { onPick(device) }
            }
        ) { EmptyView() }
        .padding(.horizontal, KdSpacing.s3)
    }
}

// MARK: - Manual add

/// Status line for a manual-add attempt; nil means the call threw.
func manualAddMessage(_ r: ManualAddResult?) -> String {
    guard let r else { return "Couldn't add the device. Try again." }
    switch r.sealedType() {
    case .added(let s): return "Added \(s.value.deviceName). Tap it above to pair."
    case .alreadyVisible(let s): return "\(s.value.deviceName) is already in the list above."
    case .thisDevice: return "That's this device."
    case .wrongDevice: return "A different device answered there. Ask for a fresh code."
    case .unreachable(let s):
        return "No Klardrop device answered at \(s.value.address). Check that Klardrop is open on it and both are on the same network."
    case .invalidInput: return "Enter a code like 37 or 12.37, or an IP address like 192.168.1.37."
    case .noLanAddress: return "This device isn't connected to Wi-Fi or Ethernet."
    }
}

private struct ManualAddSection: View {

    let manualAddInfo: () async -> ManualAddInfo?
    let onAddManually: (String) async -> ManualAddResult?

    @Environment(\.kdColors) private var kd

    @State private var text = ""
    @State private var busy = false
    @State private var result: ManualAddResult? = nil
    @State private var attempted = false
    @State private var notice: String? = nil
    @State private var showCode = false
    @State private var loadingInfo = true
    @State private var info: ManualAddInfo? = nil
    #if os(iOS)
    @State private var scanning = false
    #endif

    private var resultIsOk: Bool { isOk(result) }

    private func isOk(_ r: ManualAddResult?) -> Bool {
        guard let r else { return false }
        switch r.sealedType() {
        case .added, .alreadyVisible: return true
        default: return false
        }
    }

    #if os(iOS)
    // isAvailable is false until camera access is granted, so gate on isSupported only.
    private var canScan: Bool { DataScannerViewController.isSupported }

    private func startScan() {
        Task { @MainActor in
            var ok = false
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: ok = true
            case .notDetermined: ok = await AVCaptureDevice.requestAccess(for: .video)
            default: break
            }
            if ok {
                scanning = true
            } else {
                notice = "Camera access is off. Type the code instead."
                attempted = true
            }
        }
    }
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: KdSpacing.s3) {
            Text("Add by code or IP")
                .kdStyle(.headline, color: kd.text)

            Text("For networks that hide nearby devices. On the other device, open Add a device \u{2192} Show this device's code.")
                .kdStyle(.caption, color: kd.text2)
                .fixedSize(horizontal: false, vertical: true)

            TextField("Code or IP, e.g. 37 or 192.168.1.37", text: $text)
                .kdStyle(.body, color: kd.text)
                .padding(KdSpacing.s3)
                .background(kd.bg2)
                .overlay(KdShape.md.stroke(kd.border, lineWidth: 1))
                .clipShape(KdShape.md)
                .autocorrectionDisabled()
                #if os(iOS)
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                #endif
                .disabled(busy)
                .onSubmit(submit)

            HStack(spacing: KdSpacing.s4) {
                Button(action: submit) {
                    Text(busy ? "Checking\u{2026}" : "Add")
                        .kdStyle(.body, color: kd.accent)
                }
                .disabled(busy)

                #if os(iOS)
                if canScan {
                    Button(action: startScan) {
                        Text("Scan").kdStyle(.body, color: kd.accent)
                    }
                    .disabled(busy)
                }
                #endif
            }

            if attempted {
                Text(notice ?? manualAddMessage(result))
                    .kdStyle(.caption, color: notice == nil && resultIsOk ? kd.text2 : kd.err)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Button { showCode.toggle() } label: {
                Text(showCode ? "Hide this device's code" : "Show this device's code")
                    .kdStyle(.body, color: kd.accent)
            }

            if showCode { codeBlock }
        }
        .padding(.horizontal, KdSpacing.s5)
        .padding(.bottom, KdSpacing.s3)
        .task(id: showCode) {
            guard showCode else { return }
            loadingInfo = true
            let i = await manualAddInfo()
            guard !Task.isCancelled else { return }
            info = i
            loadingInfo = false
        }
        #if os(iOS)
        .sheet(isPresented: $scanning) {
            ZStack(alignment: .topTrailing) {
                QrScannerView { code in
                    scanning = false
                    text = code
                    submit()
                }
                .ignoresSafeArea()

                Button { scanning = false } label: {
                    Text("Cancel").kdStyle(.body, color: kd.accent)
                        .padding(KdSpacing.s3)
                        .background(kd.bg1)
                        .clipShape(KdShape.md)
                }
                .padding(KdSpacing.s4)
            }
        }
        #endif
    }

    @ViewBuilder
    private var codeBlock: some View {
        if loadingInfo {
            Text("Loading\u{2026}").kdStyle(.caption, color: kd.text2)
        } else if let info {
            VStack(spacing: KdSpacing.s2) {
                Text(info.code).kdStyle(.display, color: kd.text)
                if let cg = generateQrCode(from: info.qrPayload) {
                    Image(decorative: cg, scale: 1, orientation: .up)
                        .resizable()
                        .interpolation(.none)
                        .frame(width: 180, height: 180)
                        .padding(KdSpacing.s3)
                        .background(Color.white)
                        .clipShape(KdShape.md)
                }
                Text("\(info.address):\(info.port)").kdStyle(.caption, color: kd.text2)
            }
            .frame(maxWidth: .infinity)
        } else {
            Text("This device isn't connected to Wi-Fi or Ethernet.")
                .kdStyle(.caption, color: kd.text2)
        }
    }

    private func submit() {
        guard !busy else { return }
        let input = text.trimmingCharacters(in: .whitespacesAndNewlines)
        busy = true
        notice = nil
        Task { @MainActor in
            let r = await onAddManually(input)
            busy = false
            result = r
            attempted = true
            if isOk(r) { text = "" }
        }
    }
}

#if os(iOS)
/// Live QR scanner; reports the first decoded payload once.
private struct QrScannerView: UIViewControllerRepresentable {

    let onResult: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onResult: onResult) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {
        if !vc.isScanning { try? vc.startScanning() }
    }

    static func dismantleUIViewController(_ vc: DataScannerViewController, coordinator: Coordinator) {
        vc.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onResult: (String) -> Void
        private var done = false

        init(onResult: @escaping (String) -> Void) { self.onResult = onResult }

        func dataScanner(_ dataScanner: DataScannerViewController,
                         didAdd addedItems: [RecognizedItem],
                         allItems: [RecognizedItem]) {
            guard !done else { return }
            for item in addedItems {
                if case .barcode(let b) = item, let payload = b.payloadStringValue {
                    done = true
                    onResult(payload)
                    return
                }
            }
        }
    }
}
#endif

// MARK: - AddDevicePlaceholderSurface

/// Empty-trusted CTA card shown in DiscoveryScreen's "Your devices" section.
/// Tapping opens the AddDevicePickerSheet. Adapts layout to size class.
struct AddDevicePlaceholderSurface: View {

    let onClick: () -> Void

    @Environment(\.kdColors) private var kd
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    var body: some View {
        Button(action: onClick) {
            #if os(iOS)
            let showHorizontal = sizeClass != .compact
            #else
            let showHorizontal = true
            #endif
            if showHorizontal {
                // Sidebar / regular: horizontal layout
                HStack(spacing: KdSpacing.s3) {
                    DeviceAvatarView(kind: .unknown, style: .neutral, size: KdSpacing.s7)

                    VStack(alignment: .leading, spacing: KdSpacing.s1) {
                        Text("Add a device")
                            .kdStyle(.body, color: kd.text)
                            .lineLimit(1)
                        Text("Pair from nearby devices")
                            .kdStyle(.caption, color: kd.text2)
                            .lineLimit(1)
                    }

                    Spacer()
                }
                .padding(.horizontal, KdSpacing.s3)
                .padding(.vertical, KdSpacing.s3)
            } else {
                // Compact: centred column
                VStack(spacing: KdSpacing.s2) {
                    DeviceAvatarView(kind: .unknown, style: .neutral, size: KdSpacing.s7)

                    Text("Add a device")
                        .kdStyle(.body, color: kd.text)
                        .lineLimit(1)

                    Text("Pair from nearby")
                        .kdStyle(.caption, color: kd.text2)
                        .lineLimit(1)
                }
                .padding(.vertical, KdSpacing.s3)
                .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.plain)
        .background(kd.bg1)
        .clipShape(KdShape.lg)
    }
}
