import SwiftUI

// ---------------------------------------------------------------------------
// C09 · MessageInputView
// Pill text field + paperclip + accent send button, fixed to bottom safe area.
// Mirrors: compose-ui/.../components/MessageInput.kt
//
// Uses @Binding so the caller (DeviceChatScreen) owns the draft text state.
// desktopVariant is dropped — iOS doesn't have the drag/drop pane hint.
// ---------------------------------------------------------------------------

struct MessageInputView: View {

    @Binding var text: String
    let onSend: () -> Void
    var onAttach: () -> Void = {}
    var enabled: Bool = true

    @Environment(\.kdColors) private var kd
    @FocusState private var isFocused: Bool

    private var inputAlpha: Double { enabled ? 1.0 : 0.45 }

    var body: some View {
        HStack(spacing: 0) {
            // Leading attach / paperclip button
            Button {
                onAttach()
            } label: {
                Image(systemName: "paperclip")
                    .font(.system(size: 20, weight: .medium))
                    .foregroundColor(kd.text2.opacity(inputAlpha))
                    .frame(width: 40, height: 40)
            }
            .disabled(!enabled)

            Spacer()
                .frame(width: KdSpacing.s1)

            // Pill text field
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(kd.bg2)
                    .overlay(
                        Capsule()
                            .strokeBorder(kd.border, lineWidth: 1)
                    )

                HStack(spacing: 0) {
                    if text.isEmpty {
                        Text(enabled ? "Message" : "Input disabled")
                            .kdStyle(.body, color: kd.text3.opacity(inputAlpha))
                            .padding(.leading, KdSpacing.s3)
                    }
                    TextField("", text: $text)
                        .disabled(!enabled)
                        .focused($isFocused)
                        .font(KdTypeRole.body.font)
                        .foregroundColor(kd.text.opacity(inputAlpha))
                        .padding(.horizontal, KdSpacing.s3)
                        .submitLabel(.send)
                        .onSubmit {
                            if enabled && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                onSend()
                            }
                        }
                }
            }
            .frame(height: 40)
            .frame(maxWidth: .infinity)

            Spacer()
                .frame(width: KdSpacing.s2)

            // Accent send button (filled circle)
            Button {
                if enabled && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    onSend()
                }
            } label: {
                ZStack {
                    Circle()
                        .fill(kd.accent.opacity(inputAlpha))
                        .frame(width: 40, height: 40)
                    Image(systemName: "arrow.up")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(kd.textInv)
                }
            }
            .disabled(!enabled)
        }
        .padding(.horizontal, KdSpacing.s3)
        .padding(.vertical, KdSpacing.s2)
        .padding(.bottom, KdSpacing.s1)
    }
}

// MARK: - Previews

#Preview {
    @Previewable @State var text = ""
    VStack {
        Spacer()
        MessageInputView(text: $text, onSend: { text = "" }, onAttach: {})
    }
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
