import SwiftUI

// ---------------------------------------------------------------------------
// C07 · FileCardView
// File transfer card embedded inside a BubbleView.
// Mirrors: compose-ui/.../components/FileCard.kt
//
// Also defines the KdFileState enum (owned by this file per viewContract).
// ---------------------------------------------------------------------------

/// State of a file transfer for the FileCardView.
///
/// The associated fraction is optional: `nil` means the transfer is live but has no
/// measurable percentage yet (connecting, waiting for the recipient to accept, opening the
/// receive sink). Those phases render an indeterminate bar — painting them as a hard 0%
/// is indistinguishable from a stalled transfer.
enum KdFileState {
    case sending(Double?)
    case receiving(Double?)
    case done
    case failed
}

struct FileCardView: View {

    let fileName: String
    var fileSize: String? = nil
    let state: KdFileState
    var onRetry: () -> Void = {}

    @Environment(\.kdColors) private var kd

    private var isClickable: Bool {
        if case .failed = state { return true }
        return false
    }

    var body: some View {
        HStack(spacing: KdSpacing.s3) {
            // 40pt icon tile
            ZStack {
                RoundedRectangle(cornerRadius: KdRadii.sm, style: .continuous)
                    .fill(kd.bg3)
                    .frame(width: 40, height: 40)
                Image(systemName: "doc.fill")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundColor(kd.text2)
            }

            VStack(alignment: .leading, spacing: 2) {
                // File name + done check
                HStack(spacing: KdSpacing.s2) {
                    Text(fileName)
                        .kdStyle(.mono, color: kd.text)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    if case .done = state {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(kd.ok)
                    }
                }

                // File size
                if let size = fileSize {
                    Text(size)
                        .kdStyle(.caption, color: kd.text3)
                }

                // Progress bar or failure message
                switch state {
                case .sending(let progress):
                    progressBar(value: progress)
                case .receiving(let progress):
                    progressBar(value: progress)
                case .failed:
                    Text("Failed · Tap to retry")
                        .kdStyle(.caption, color: kd.err)
                case .done:
                    EmptyView()
                }
            }
        }
        .padding(.vertical, KdSpacing.s2)
        .frame(minWidth: 240, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture {
            if isClickable { onRetry() }
        }
    }

    // MARK: - Progress bar

    @ViewBuilder
    private func progressBar(value: Double?) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(kd.text.opacity(0.08))
                    .frame(height: 4)

                if let value {
                    Capsule()
                        .fill(kd.accent)
                        .frame(width: geo.size.width * CGFloat(max(0, min(1, value))), height: 4)
                } else {
                    IndeterminateBar(trackWidth: geo.size.width, color: kd.accent)
                }
            }
        }
        .frame(height: 4)
    }
}

// MARK: - IndeterminateBar

/// Continuously sweeping segment used while a transfer is live but unmeasurable — mirrors
/// Compose's `LinearProgressIndicator` without a `progress` argument.
private struct IndeterminateBar: View {
    let trackWidth: CGFloat
    let color: Color

    @State private var sweeping = false

    private var segmentWidth: CGFloat { max(24, trackWidth * 0.35) }

    var body: some View {
        Capsule()
            .fill(color)
            .frame(width: segmentWidth, height: 4)
            // Start fully off the leading edge, finish fully off the trailing edge.
            .offset(x: sweeping ? trackWidth : -segmentWidth)
            .animation(
                .easeInOut(duration: 1.1).repeatForever(autoreverses: false),
                value: sweeping
            )
            .onAppear { sweeping = true }
            // The bar is clipped to the track so the segment doesn't paint past the card.
            .frame(width: trackWidth, height: 4, alignment: .leading)
            .clipShape(Capsule())
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: KdSpacing.s4) {
        FileCardView(fileName: "presentation.pdf", fileSize: "2.4 MB", state: .sending(0.45))
        FileCardView(fileName: "photo.jpg", fileSize: "1.1 MB", state: .receiving(0.8))
        FileCardView(fileName: "waiting-on-peer.iso", fileSize: "4.0 GB", state: .sending(nil))
        FileCardView(fileName: "archive.zip", fileSize: "15.0 MB", state: .done)
        FileCardView(fileName: "broken_file.mp4", fileSize: "80 MB", state: .failed, onRetry: {})
    }
    .padding()
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
