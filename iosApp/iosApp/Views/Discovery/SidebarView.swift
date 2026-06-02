import SwiftUI

// ---------------------------------------------------------------------------
// C16 · SidebarView
// Floating sidebar sheet for iPad split view.
// Mirrors: compose-ui/.../components/Sidebar.kt
// ---------------------------------------------------------------------------

struct SidebarView<Yours: View, Nearby: View>: View {
    var width: CGFloat = 320
    @ViewBuilder let yoursSection: () -> Yours
    @ViewBuilder let nearbySection: () -> Nearby
    let localDeviceName: String
    var localDeviceSub: String? = nil
    var onLocalDeviceTap: () -> Void = {}

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(spacing: 0) {
            // Scrollable sections
            ScrollView {
                VStack(spacing: 0) {
                    yoursSection()
                    Spacer().frame(height: KdSpacing.s4)
                    nearbySection()
                    Spacer().frame(height: KdSpacing.s2)
                }
            }

            Divider()
                .background(kd.border)

            // Local device footer
            LocalDeviceFooter(
                name: localDeviceName,
                sub: localDeviceSub,
                onTap: onLocalDeviceTap
            )
        }
        .frame(width: width)
        .background(kd.bg1)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(kd.border, lineWidth: 1)
        )
    }
}

// MARK: - Local device footer

private struct LocalDeviceFooter: View {
    let name: String
    let sub: String?
    let onTap: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: KdSpacing.s3) {
                DeviceAvatarView(
                    kind: .mac,
                    style: .tinted,
                    status: .ok,
                    size: 40
                )

                VStack(alignment: .leading, spacing: 2) {
                    Text(name.isEmpty ? "This device" : name)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(kd.text)
                        .lineLimit(1)

                    if let sub, !sub.isEmpty {
                        Text(sub)
                            .kdStyle(.caption, color: kd.text3)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Image(systemName: "pencil")
                    .font(.system(size: 14, weight: .regular))
                    .foregroundColor(kd.text3)
                    .frame(width: 28, height: 28)
            }
            .padding(.horizontal, KdSpacing.s3)
            .padding(.vertical, KdSpacing.s3)
        }
        .buttonStyle(.plain)
    }
}
