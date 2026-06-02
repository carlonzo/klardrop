import SwiftUI

// ---------------------------------------------------------------------------
// TextMessageViewerView
// Full-text viewer for long messages that overflow the bubble height cap.
// Presented as a bottom sheet (.large detent) on both iPhone and iPad.
// Mirrors: compose-ui/.../chat/TextMessageViewer.kt (mobile sheet path)
// ---------------------------------------------------------------------------

struct TextMessageViewerView: View {

    let text: String
    let onCopy: () -> Void
    let onDismiss: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // "Message" section head
            SectionHeadView(label: "Message")
                .padding(.top, KdSpacing.s3)
                .padding(.horizontal, KdSpacing.s4)

            Spacer()
                .frame(height: KdSpacing.s2)

            // Scrollable selectable text
            ScrollView {
                Text(text)
                    .kdStyle(.body, color: kd.text, multiline: true)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, KdSpacing.s4)
                    .padding(.bottom, KdSpacing.s3)
            }

            // Footer: Copy + Close buttons (right-aligned)
            HStack(spacing: KdSpacing.s1) {
                Spacer()

                Button {
                    onCopy()
                } label: {
                    HStack(spacing: KdSpacing.s1) {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 14, weight: .medium))
                        Text("Copy")
                            .kdStyle(.body, color: kd.accent)
                    }
                    .foregroundColor(kd.accent)
                    .padding(.horizontal, KdSpacing.s3)
                    .padding(.vertical, KdSpacing.s2)
                }
                .buttonStyle(.plain)

                Button {
                    onDismiss()
                } label: {
                    Text("Close")
                        .kdStyle(.body, color: kd.text2)
                        .padding(.horizontal, KdSpacing.s3)
                        .padding(.vertical, KdSpacing.s2)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, KdSpacing.s3)
            .padding(.bottom, KdSpacing.s3)
        }
        .background(kd.bg1)
    }
}

// MARK: - Previews

#Preview {
    Color.gray
        .ignoresSafeArea()
        .sheet(isPresented: .constant(true)) {
            TextMessageViewerView(
                text: "This is a very long message that overflows the bubble height. " +
                      String(repeating: "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ", count: 8),
                onCopy: {},
                onDismiss: {}
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(KdRadii.sheet)
        }
        .kdColorsEnvironment()
}
