import SwiftUI

// ---------------------------------------------------------------------------
// C14 · ChatHeaderView
// Avatar + device name + reachability subline.
// Used as the inline header for the iPad chat pane (toolbarVariant path).
// On iPhone the NavigationStack toolbar renders a compact equivalent.
// Mirrors: compose-ui/.../components/ChatHeader.kt
// ---------------------------------------------------------------------------

struct ChatHeaderView: View {

    let deviceName: String
    var subText: String = ""
    var kind: KdDeviceKind = .unknown
    var avatarStyle: KdAvatarStyle = .neutral
    var status: KdStatus? = nil
    var isReachable: Bool = true
    var avatarSize: CGFloat = 32

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: KdSpacing.s2) {
                DeviceAvatarView(kind: kind, style: avatarStyle, status: status, size: avatarSize)

                Spacer()
                    .frame(width: KdSpacing.s2)

                VStack(alignment: .leading, spacing: 2) {
                    Text(deviceName)
                        .kdStyle(.body, color: kd.text)
                        .fontWeight(.semibold)
                        .lineLimit(1)

                    if !subText.isEmpty {
                        Text(subText)
                            .kdStyle(.caption, color: isReachable ? kd.trust : kd.err)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(height: 56)
            .padding(.horizontal, KdSpacing.s4)

            Divider()
                .background(kd.divider)
        }
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: 0) {
        ChatHeaderView(
            deviceName: "Carlo's MacBook Pro",
            subText: "Offline",
            kind: .mac,
            avatarStyle: .neutral,
            status: .err,
            isReachable: false
        )
        ChatHeaderView(
            deviceName: "iPhone 15 Pro",
            subText: "",
            kind: .iphone,
            avatarStyle: .tinted,
            status: .ok,
            isReachable: true
        )
    }
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
