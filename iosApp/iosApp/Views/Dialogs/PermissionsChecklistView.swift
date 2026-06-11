import SwiftUI

// ---------------------------------------------------------------------------
// PermissionsChecklistView — C13 · dialogs cluster
//
// Warn-toned checklist panel that collapses with animation when all items are
// granted. Each ungranted row shows a circle icon, title/caption, and an
// "Allow >" tappable label. Mirrors components/PermissionsChecklist.kt.
// ---------------------------------------------------------------------------

/// A single permission item displayed in the checklist.
struct KdPermissionItem: Identifiable {
    let id: String
    let title: String
    let caption: String
    let isGranted: Bool
}

struct PermissionsChecklistView: View {

    let items: [KdPermissionItem]
    var onAllow: (KdPermissionItem) -> Void = { _ in }

    @Environment(\.kdColors) private var kd

    private var allGranted: Bool { items.allSatisfy(\.isGranted) }
    private var pendingItems: [KdPermissionItem] { items.filter { !$0.isGranted } }

    var body: some View {
        // Animate the whole panel out when all items become granted.
        if !allGranted {
            VStack(alignment: .leading, spacing: KdSpacing.s2) {
                Text("Permissions needed")
                    .kdStyle(.overline, color: kd.warn)
                    .textCase(.uppercase)

                ForEach(pendingItems) { item in
                    PermissionRowView(item: item, onAllow: { onAllow(item) })
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(KdSpacing.s3)
            .background(kd.warn.opacity(0.10))
            .clipShape(KdShape.md)
            .transition(.asymmetric(
                insertion: .move(edge: .top).combined(with: .opacity),
                removal: .move(edge: .top).combined(with: .opacity)
            ))
            .animation(.easeInOut(duration: 0.22), value: allGranted)
        }
    }
}

// MARK: - Private row

private struct PermissionRowView: View {
    let item: KdPermissionItem
    let onAllow: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        HStack(spacing: KdSpacing.s3) {
            Image(systemName: item.isGranted ? "checkmark.circle" : "circle")
                .font(.system(size: 20, weight: .regular))
                .foregroundColor(item.isGranted ? kd.ok : kd.warn)
                .frame(width: 20, height: 20)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .kdStyle(.body, color: kd.text)
                    .fixedSize(horizontal: false, vertical: true)
                if !item.caption.isEmpty {
                    Text(item.caption)
                        .kdStyle(.caption, color: kd.text2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if !item.isGranted {
                Text("Allow \u{203A}")
                    .kdStyle(.body, color: kd.accent)
                    .onTapGesture(perform: onAllow)
                    .padding(.horizontal, KdSpacing.s1)
            }
        }
    }
}
