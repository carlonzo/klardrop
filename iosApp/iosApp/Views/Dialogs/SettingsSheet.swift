import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// SettingsSheet + ReportProblemSheet — dialogs cluster
//
// Settings bottom sheet: Title + (Android-only) background-discovery Toggle,
// else 'No settings available'. On iOS showBackgroundDiscoveryToggle is false
// (controller.supportsBackgroundDiscovery == false) so it shows the empty
// message — kept for parity with discovery_screen.kt SettingsSheet.
//
// ReportProblemSheet lives here too (same cluster): it is what the overflow
// menu in the discovery header / sidebar footer opens. Mirrors
// compose-ui/.../ReportProblem.kt.
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

// MARK: - ReportProblemSheet

/// Sends the user's description to Sentry as user feedback, which carries the last 100 log
/// breadcrumbs along with it (see CrashReporter.reportUserFeedback in commonMain).
///
/// This exists because the failures worth reporting do not crash: a transfer that would not
/// connect leaves nothing for a crash reporter to find, and "it didn't work" on its own is not
/// actionable — the log tail leading up to it is the whole value.
///
/// Mirrors: compose-ui/.../ReportProblem.kt ReportProblemForm.
struct ReportProblemSheet: View {

    let onDismiss: () -> Void

    @Environment(\.kdColors) private var kd
    @FocusState private var descriptionFocused: Bool

    @State private var description: String = ""
    @State private var email: String = ""
    @State private var outcome: ReportOutcome? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: KdSpacing.s3) {
            Text("Report a problem")
                .kdStyle(.headline, color: kd.text)

            Text("Describe what happened. The recent activity log is attached automatically.")
                .kdStyle(.caption, color: kd.text2)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)

            // Styled to match RenameSheet's field, which mirrors the Compose OutlinedTextField.
            TextEditor(text: $description)
                .kdStyle(.body, color: kd.text)
                .frame(minHeight: 96)
                .scrollContentBackground(.hidden)
                .padding(KdSpacing.s3)
                .background(kd.bg2)
                .overlay(
                    KdShape.md.stroke(descriptionFocused ? kd.accent : kd.border, lineWidth: 1)
                )
                .clipShape(KdShape.md)
                .focused($descriptionFocused)

            TextField("Email (optional)", text: $email)
                .kdStyle(.body, color: kd.text)
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .keyboardType(.emailAddress)
                #endif
                .autocorrectionDisabled(true)
                .padding(KdSpacing.s3)
                .background(kd.bg2)
                .overlay(KdShape.md.stroke(kd.border, lineWidth: 1))
                .clipShape(KdShape.md)
                .submitLabel(.done)

            if let outcome {
                Text(message(for: outcome))
                    .kdStyle(.caption, color: isSent(outcome) ? kd.text2 : kd.err)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack {
                Spacer()
                Button(isSent(outcome) ? "Close" : "Cancel") { onDismiss() }
                    .kdStyle(.body, color: kd.text2)
                    .buttonStyle(.plain)

                Spacer().frame(width: KdSpacing.s2)

                Button("Send") { send() }
                    .kdStyle(.body, color: canSend ? kd.accent : kd.text3)
                    .buttonStyle(.plain)
                    .disabled(!canSend)
            }
        }
        .padding(.horizontal, KdSpacing.s6)
        .padding(.top, KdSpacing.s4)
        .padding(.bottom, KdSpacing.s6)
        .frame(maxWidth: .infinity, alignment: .leading)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #else
        .frame(minWidth: 420, minHeight: 360)
        .background(kd.bg1)
        #endif
    }

    private var canSend: Bool {
        !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func send() {
        // `name` is passed explicitly rather than leaning on the Kotlin default argument, so this
        // call does not depend on SKIE's default-argument bridging staying enabled.
        outcome = CrashReporter.shared.reportUserFeedback(
            comments: description,
            name: nil,
            email: email
        )
        if outcome == .sent {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
                onDismiss()
            }
        }
    }

    private func isSent(_ outcome: ReportOutcome?) -> Bool {
        guard let outcome else { return false }
        switch outcome {
        case .sent: return true
        default:    return false
        }
    }

    private func message(for outcome: ReportOutcome) -> String {
        switch outcome {
        case .sent:     return "Thanks \u{2014} report sent."
        case .disabled: return "Reporting is turned off in this build, so nothing was sent."
        default:        return "Could not send the report. Please try again later."
        }
    }
}

// MARK: - AppMenuButton

/// App-level overflow menu — the "..." in the discovery header (compact) and the sidebar footer
/// (regular / macOS). Its only entry is "Report a problem": SettingsSheet above has nothing on it
/// for Apple platforms, so a gear would open a dead end.
///
/// Mirrors: compose-ui/.../WideLayout.kt AppMenu.
struct AppMenuButton: View {
    let onReportProblem: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        Menu {
            Button(action: onReportProblem) {
                Label("Report a problem", systemImage: "exclamationmark.bubble")
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: KdSpacing.s4, weight: .regular))
                .foregroundColor(kd.text2)
                .frame(width: KdSpacing.s6, height: KdSpacing.s6)
        }
        .accessibilityLabel("More options")
    }
}
