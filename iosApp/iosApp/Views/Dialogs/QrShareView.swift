import SwiftUI
import CoreImage
import presentation

// ---------------------------------------------------------------------------
// QrShareView — C12 · QR Share bottom sheet content
//
// Displays a LAN HTTPS share session with a QR code generated via CoreImage
// CIFilter(name: "CIQRCodeGenerator"), error correction M, quiet zone, and
// always dark-on-light rendering. Collects session.state via SKIE StateFlow.
//
// Mirrors components/QrShareSheet.kt.
// ---------------------------------------------------------------------------

private let ciContext = CIContext()

private let qrHelperTextFiles =
    "Keep this screen open until you see sending progress. Then you can hide it — the download will finish. Each scan is one phone; the code changes after someone opens it. Both devices must be on the same Wi-Fi. If the other phone warns about the certificate, tap Proceed — that’s this device. Open in Safari if the camera preview blocks it. Some guest networks block this. Turn off mobile data if it won’t open."

struct QrShareView: View {

    let session: QrShareSession
    var onDismiss: (() -> Void)? = nil

    @State private var state: QrShareState
    @State private var qrImage: CGImage? = nil

    @Environment(\.kdColors) private var kd

    init(session: QrShareSession, onDismiss: (() -> Void)? = nil) {
        self.session = session
        self.onDismiss = onDismiss
        _state = State(initialValue: session.state.value)
    }

    private var currentUrl: String? {
        switch onEnum(of: state) {
        case .qrVisible(let s):
            return s.url
        case .serving(let s):
            return s.qrStillVisible ? s.url : nil
        default:
            return nil
        }
    }

    private var payloadSummary: String? {
        switch onEnum(of: state) {
        case .qrVisible(let s):
            return s.payloadSummary.isEmpty ? nil : s.payloadSummary
        default:
            return nil
        }
    }

    private var downloads: [QrDownloadProgress] {
        switch onEnum(of: state) {
        case .serving(let s):
            return s.downloads
        default:
            return []
        }
    }

    private var errorMessage: String? {
        switch onEnum(of: state) {
        case .failed(let s):
            return s.message
        default:
            return nil
        }
    }

    private var isFailed: Bool {
        if case .failed = onEnum(of: state) { return true }
        return false
    }

    private var isStarting: Bool {
        if case .starting = onEnum(of: state) { return true }
        return false
    }

    private var isIdle: Bool {
        if case .idle = onEnum(of: state) { return true }
        return false
    }

    private var buttonLabel: String {
        switch onEnum(of: state) {
        case .failed:
            return "Dismiss"
        case .serving(let s):
            if !s.downloads.isEmpty || !s.qrStillVisible {
                return "Hide — keeps sending"
            }
            return "Dismiss"
        default:
            return "Dismiss"
        }
    }

    var body: some View {
        VStack(alignment: .center, spacing: 0) {
            // Drag handle
            Capsule()
                .fill(kd.text3.opacity(0.40))
                .frame(width: 40, height: 4)
                .frame(maxWidth: .infinity)
                .padding(.top, KdSpacing.s2)

            Spacer().frame(height: KdSpacing.s4)

            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: KdSpacing.s4) {
                    // Header title & payload summary
                    VStack(spacing: KdSpacing.s1) {
                        Text("Share via QR")
                            .kdStyle(.headline, color: kd.text)
                            .multilineTextAlignment(.center)

                        if let payloadSummary {
                            Text(payloadSummary)
                                .kdStyle(.caption, color: kd.text2)
                                .lineLimit(1)
                                .multilineTextAlignment(.center)
                        }
                    }
                    .padding(.horizontal, KdSpacing.s4)

                    // Main display depending on state
                    if isStarting {
                        VStack(spacing: KdSpacing.s3) {
                            ProgressView()
                                .tint(kd.accent)
                            Text("Starting QR share…")
                                .kdStyle(.body, color: kd.text2)
                        }
                        .padding(.vertical, KdSpacing.s6)
                    } else if let errorMessage {
                        VStack(spacing: KdSpacing.s3) {
                            ZStack {
                                Circle()
                                    .fill(kd.err.opacity(0.12))
                                    .frame(width: 48, height: 48)
                                Image(systemName: "exclamationmark.circle")
                                    .font(.system(size: 28))
                                    .foregroundColor(kd.err)
                            }
                            Text(errorMessage)
                                .kdStyle(.body, color: kd.err)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, KdSpacing.s4)
                        }
                        .padding(.vertical, KdSpacing.s4)
                    } else if let currentUrl {
                        VStack(spacing: KdSpacing.s3) {
                            // QR image
                            if let qrImage {
                                Image(decorative: qrImage, scale: 1.0, orientation: .up)
                                    .resizable()
                                    .interpolation(.none)
                                    .scaledToFit()
                                    .frame(width: 200, height: 200)
                                    .padding(16)
                                    .background(Color.white)
                                    .clipShape(KdShape.md)
                            } else {
                                ProgressView()
                                    .frame(width: 220, height: 220)
                            }

                            // Monospace URL under QR
                            Text(currentUrl)
                                .kdStyle(.mono, color: kd.text2)
                                .textSelection(.enabled)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, KdSpacing.s4)
                                .accessibilityLabel(currentUrl)
                        }
                    }

                    // Live download progress
                    if !downloads.isEmpty {
                        DownloadsProgressSectionView(downloads: downloads)
                            .padding(.horizontal, KdSpacing.s4)
                    }

                    // Helper text
                    if !isFailed {
                        Text(qrHelperTextFiles)
                            .kdStyle(.caption, color: kd.text2)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, KdSpacing.s5)
                    }
                }
            }

            Spacer().frame(height: KdSpacing.s4)

            // Primary CTA
            Button(action: {
                if case .failed = onEnum(of: state) {
                    session.cancel()
                } else {
                    session.dismissQrSheet()
                }
                onDismiss?()
            }) {
                Text(buttonLabel)
                    .kdStyle(.body, color: kd.textInv)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
            }
            .background(kd.accent)
            .clipShape(KdShape.md)
            .padding(.horizontal, KdSpacing.s4)

            // Cancel transfer button
            if !isIdle && !isFailed {
                Spacer().frame(height: KdSpacing.s2)
                Button(action: {
                    session.cancel()
                    onDismiss?()
                }) {
                    Text("Cancel transfer")
                        .kdStyle(.caption, color: kd.err)
                        .padding(.horizontal, KdSpacing.s4)
                }
            }

            Spacer().frame(height: KdSpacing.s7)
        }
        .task {
            for await next in session.state {
                self.state = next
            }
        }
        .task(id: currentUrl) {
            if let currentUrl, !currentUrl.isEmpty {
                self.qrImage = generateQrCode(from: currentUrl)
            } else {
                self.qrImage = nil
            }
        }
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #else
        .frame(minWidth: 420, minHeight: 360)
        .background(kd.bg1)
        #endif
    }
}

// MARK: - CoreImage QR Generation

private func generateQrCode(from urlString: String) -> CGImage? {
    guard !urlString.isEmpty, let data = urlString.data(using: .utf8) else { return nil }
    guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
    filter.setValue(data, forKey: "inputMessage")
    filter.setValue("M", forKey: "inputCorrectionLevel")
    guard let outputImage = filter.outputImage else { return nil }

    // CIQRCodeGenerator produces 1 pixel per module with black modules on white.
    // Scale up using nearest-neighbor so CIContext produces a crisp high-res image.
    let scale: CGFloat = 10
    let transform = CGAffineTransform(scaleX: scale, y: scale)
    let scaledImage = outputImage.transformed(by: transform)

    return ciContext.createCGImage(scaledImage, from: scaledImage.extent)
}

// MARK: - Download progress row

private struct DownloadsProgressSectionView: View {

    let downloads: [QrDownloadProgress]
    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(spacing: KdSpacing.s2) {
            ForEach(downloads, id: \.fileName) { download in
                VStack(spacing: KdSpacing.s1) {
                    HStack {
                        Text(download.fileName)
                            .kdStyle(.caption, color: kd.text)
                            .lineLimit(1)
                        Spacer()
                        Text("\(download.percentage)%")
                            .kdStyle(.mono, color: kd.text)
                    }

                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule()
                                .fill(kd.bg3)
                                .frame(height: 4)
                            Capsule()
                                .fill(kd.accent)
                                .frame(
                                    width: max(0, min(geo.size.width * CGFloat(download.percentage) / 100.0, geo.size.width)),
                                    height: 4
                                )
                        }
                    }
                    .frame(height: 4)
                }
                .padding(.horizontal, KdSpacing.s3)
                .padding(.vertical, KdSpacing.s2)
                .background(kd.bg2)
                .clipShape(KdShape.md)
            }
        }
    }
}
