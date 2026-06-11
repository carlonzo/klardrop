import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// UpdateBannerView
// Dismissible "update available" banner.
// Mirrors: compose-ui/.../components/UpdateBanner.kt
//
// Renders nothing unless UpdateStatus is Available (invisible on iOS where
// the update channel is always Unknown/UpToDate — kept for parity).
// Per-version dismissal via @State so a new release re-shows the banner.
// ---------------------------------------------------------------------------

struct UpdateBannerView: View {
    let status: UpdateStatus
    let installProgress: InstallProgress
    let onAction: (UpdateAction) -> Bool
    let onRestart: () -> Void

    @State private var dismissedVersion: String? = nil
    @State private var copied = false

    @Environment(\.kdColors) private var kd

    var body: some View {
        // Only show for Available status
        switch onEnum(of: status) {
        case .available(let available):
            if dismissedVersion == available.version {
                EmptyView()
            } else {
                bannerContent(available: available)
            }
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func bannerContent(available: UpdateStatusAvailable) -> some View {
        let action = available.action
        let fallbackLabel: String = {
            switch onEnum(of: action) {
            case .runCommand:
                return copied ? "Copied!" : "Copy command"
            case .openUrl:
                return "Download"
            }
        }()
        let fallbackDetail: String = {
            switch onEnum(of: action) {
            case .runCommand(let r):
                return r.command
            case .openUrl:
                return "A new version is ready to download."
            }
        }()

        let (detail, actionLabel, actionHandler) = resolveLabels(
            installProgress: installProgress,
            fallbackDetail: fallbackDetail,
            fallbackLabel: fallbackLabel,
            action: action
        )

        HStack(spacing: KdSpacing.s3) {
            // Info icon tile
            ZStack {
                Circle()
                    .fill(kd.text.opacity(0.04))
                    .frame(width: 28, height: 28)
                Image(systemName: "info.circle.fill")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 16, height: 16)
                    .foregroundColor(kd.accent)
            }

            // Title + detail
            VStack(alignment: .leading, spacing: 2) {
                Text("Update available \u{2014} \(available.version)")
                    .kdStyle(.body, color: kd.text)
                    .lineLimit(1)
                Text(detail)
                    .kdStyle(.caption, color: kd.text2)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // Primary action pill (hidden while downloading)
            if let label = actionLabel, let handler = actionHandler {
                Button(action: handler) {
                    Text(label)
                        .kdStyle(.caption, color: kd.accent)
                        .padding(.horizontal, KdSpacing.s3)
                        .padding(.vertical, KdSpacing.s1)
                        .background(kd.accent.opacity(0.16))
                        .clipShape(KdShape.md)
                }
                .buttonStyle(.plain)
            }

            // Dismiss
            Button {
                dismissedVersion = available.version
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(kd.text3)
                    .frame(width: 24, height: 24)
                    .background(Circle().fill(kd.bg2))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, KdSpacing.s3)
        .padding(.vertical, 10)
        .background(kd.accent.opacity(0.10))
        .clipShape(KdShape.md)
        .padding(.horizontal, KdSpacing.s3)
        .padding(.vertical, KdSpacing.s2)
    }

    private func resolveLabels(
        installProgress: InstallProgress,
        fallbackDetail: String,
        fallbackLabel: String,
        action: UpdateAction
    ) -> (String, String?, (() -> Void)?) {
        switch onEnum(of: installProgress) {
        case .downloading(let d):
            // d.fraction is KotlinFloat? (boxed NSNumber) — extract via floatValue.
            let pctStr: String = d.fraction.map { " \(Int($0.floatValue * 100))%" } ?? "\u{2026}"
            return ("Downloading update\(pctStr)", nil, nil)
        case .ready:
            return ("Update downloaded \u{2014} restart to apply.", "Restart", onRestart)
        default:
            return (fallbackDetail, fallbackLabel, {
                if onAction(action) { copied = true }
            })
        }
    }
}
