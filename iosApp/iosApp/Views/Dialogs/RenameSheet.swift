import SwiftUI

// ---------------------------------------------------------------------------
// RenameSheet — dialogs cluster
//
// Bottom sheet (compact) / card (regular) to rename the local device:
// TextField + Cancel / Save. Mirrors discovery_screen.kt RenameSheet.
// onSave -> model.saveCustomDeviceName(name).
// ---------------------------------------------------------------------------

struct RenameSheet: View {

    let currentName: String
    let onDismiss: () -> Void
    let onSave: (String) -> Void

    @Environment(\.kdColors) private var kd

    @State private var newName: String = ""
    @FocusState private var fieldFocused: Bool

    // Seed from currentName via .onAppear so we don't mutate during init.
    var body: some View {
        VStack(alignment: .leading, spacing: KdSpacing.s4) {
            Text("Rename device")
                .kdStyle(.headline, color: kd.text)

            Text("This is how others will see you when sharing.")
                .kdStyle(.body, color: kd.text2)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)

            // Native TextField styled to match the Compose OutlinedTextField
            TextField("Device name", text: $newName)
                .kdStyle(.body, color: kd.text)
                .padding(KdSpacing.s3)
                .background(kd.bg2)
                .overlay(
                    KdShape.md
                        .stroke(fieldFocused ? kd.accent : kd.border, lineWidth: 1)
                )
                .clipShape(KdShape.md)
                .focused($fieldFocused)
                .submitLabel(.done)
                .onSubmit { handleSave() }

            // Button row — right-aligned Cancel / Save
            HStack {
                Spacer()
                Button("Cancel") {
                    onDismiss()
                }
                .kdStyle(.body, color: kd.text2)
                .buttonStyle(.plain)

                Spacer().frame(width: KdSpacing.s2)

                Button("Save") {
                    handleSave()
                }
                .kdStyle(.body, color: kd.accent)
                .buttonStyle(.plain)
                .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(.horizontal, KdSpacing.s6)
        .padding(.top, KdSpacing.s4)
        .padding(.bottom, KdSpacing.s6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            newName = currentName
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                fieldFocused = true
            }
        }
        #if os(iOS)
        .presentationDetents([.height(280)])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #else
        .frame(minWidth: 420, minHeight: 220)
        .background(kd.bg1)
        #endif
    }

    private func handleSave() {
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        onSave(trimmed)
    }
}
