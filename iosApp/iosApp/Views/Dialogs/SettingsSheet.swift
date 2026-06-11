import SwiftUI

// ---------------------------------------------------------------------------
// SettingsSheet — dialogs cluster
//
// Settings bottom sheet: Title + (Android-only) background-discovery Toggle,
// else 'No settings available'. On iOS showBackgroundDiscoveryToggle is false
// (controller.supportsBackgroundDiscovery == false) so it shows the empty
// message — kept for parity with discovery_screen.kt SettingsSheet.
// ---------------------------------------------------------------------------

struct SettingsSheet: View {

    @Binding var backgroundDiscoveryEnabled: Bool
    let showBackgroundDiscoveryToggle: Bool
    let onBackgroundDiscoveryChange: (Bool) -> Void
    let onDismiss: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Title
            Text("Settings")
                .kdStyle(.title, color: kd.text)
                .padding(.horizontal, KdSpacing.s4)
                .padding(.top, KdSpacing.s4)
                .padding(.bottom, KdSpacing.s4)

            if showBackgroundDiscoveryToggle {
                backgroundDiscoveryRow
            } else {
                Text("No settings available on this platform yet.")
                    .kdStyle(.caption, color: kd.text2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, KdSpacing.s4)
            }

            Spacer(minLength: KdSpacing.s6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        #if os(iOS)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #else
        .frame(minWidth: 420, minHeight: 280)
        .background(kd.bg1)
        #endif
    }

    // MARK: - Background-discovery toggle row (Android only; hidden on iOS)

    private var backgroundDiscoveryRow: some View {
        HStack(alignment: .top, spacing: KdSpacing.s3) {
            VStack(alignment: .leading, spacing: KdSpacing.s1) {
                Text("Stay discoverable in background")
                    .kdStyle(.body, color: kd.text)

                Text("Keep this device visible and able to receive when the app is closed. Shows a persistent notification and uses more battery.")
                    .kdStyle(.caption, color: kd.text2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: Binding(
                get: { backgroundDiscoveryEnabled },
                set: { newValue in
                    backgroundDiscoveryEnabled = newValue
                    onBackgroundDiscoveryChange(newValue)
                }
            ))
            .labelsHidden()
            .tint(kd.accent)
        }
        .padding(.horizontal, KdSpacing.s4)
    }
}
