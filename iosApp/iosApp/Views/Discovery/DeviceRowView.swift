import SwiftUI

// ---------------------------------------------------------------------------
// C02 · DeviceRowView
// 56pt device list row: avatar + name/subText + trailing slot.
// Mirrors: compose-ui/.../components/DeviceRow.kt
// ---------------------------------------------------------------------------

/// Row visual state — drives background and text colours.
/// Owned by this file; referenced by Sidebar, DiscoveryScreen, etc.
enum KdRowState {
    case idle
    case hover
    case active
    case pairing
    case unreachable
    case pairPrompt
}

struct DeviceRowView<Trailing: View>: View {
    let name: String
    var subText: String? = nil
    var kind: KdDeviceKind = .unknown
    var avatarStyle: KdAvatarStyle = .neutral
    var rowState: KdRowState = .idle
    var status: KdStatus? = nil
    var onTap: () -> Void = {}
    @ViewBuilder let trailing: () -> Trailing

    @Environment(\.kdColors) private var kd

    // MARK: - Derived tokens

    private var rowBg: Color {
        switch rowState {
        case .active:  return kd.trustBg
        case .hover:   return kd.bg3
        case .pairing: return kd.bg3
        default:       return .clear
        }
    }

    private var nameColor: Color {
        switch rowState {
        case .active:      return kd.trust
        case .unreachable: return kd.err
        default:           return kd.text
        }
    }

    private var subColor: Color {
        switch rowState {
        case .active:      return kd.trust
        case .pairing:     return kd.warn
        case .unreachable: return kd.err
        default:           return kd.text2
        }
    }

    private var nameFontWeight: Font.Weight {
        rowState == .active ? .semibold : .medium
    }

    // MARK: - Body

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: KdSpacing.s3) {
                DeviceAvatarView(
                    kind: kind,
                    style: avatarStyle,
                    status: status,
                    size: 36
                )

                VStack(alignment: .leading, spacing: 2) {
                    Text(name)
                        .font(.system(size: 15, weight: nameFontWeight))
                        .foregroundColor(nameColor)
                        .lineLimit(1)

                    if let sub = subText {
                        Text(sub)
                            .kdStyle(.caption, color: subColor)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                trailing()
            }
            .padding(.horizontal, KdSpacing.s3)
            .frame(height: 56)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(rowBg)
            .clipShape(KdShape.lg)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Convenience init (no trailing)

extension DeviceRowView where Trailing == EmptyView {
    init(
        name: String,
        subText: String? = nil,
        kind: KdDeviceKind = .unknown,
        avatarStyle: KdAvatarStyle = .neutral,
        rowState: KdRowState = .idle,
        status: KdStatus? = nil,
        onTap: @escaping () -> Void = {}
    ) {
        self.name = name
        self.subText = subText
        self.kind = kind
        self.avatarStyle = avatarStyle
        self.rowState = rowState
        self.status = status
        self.onTap = onTap
        self.trailing = { EmptyView() }
    }
}

#Preview {
    VStack(spacing: 0) {
        DeviceRowView(
            name: "Carlo's MacBook Pro",
            subText: "Sending\u{2026}",
            kind: .mac,
            avatarStyle: .tinted,
            rowState: .active,
            status: .ok,
            onTap: {}
        ) {
            Image(systemName: "ellipsis")
                .foregroundColor(.gray)
        }
        DeviceRowView(
            name: "iPhone 16 Pro",
            kind: .iphone,
            avatarStyle: .neutral,
            rowState: .pairPrompt,
            onTap: {}
        ) {
            Text("+ Pair")
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(.gray)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.gray.opacity(0.15))
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        DeviceRowView(
            name: "Offline Device",
            subText: "Offline",
            kind: .mac,
            avatarStyle: .tinted,
            rowState: .unreachable,
            status: .err
        )
    }
    .padding(.horizontal, 12)
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
