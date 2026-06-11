import SwiftUI

// ---------------------------------------------------------------------------
// C06 · BubbleView
// Chat message bubble with asymmetric corner radii.
// Mirrors: compose-ui/.../components/Bubble.kt
// ---------------------------------------------------------------------------

/// Directionality of a chat bubble — incoming vs outgoing.
enum KdBubbleDirection {
    case incoming
    case outgoing
}

/// Delivery acknowledgment state for the bubble foot row.
enum KdDeliveryState {
    case sending
    case sent
    case delivered
    case failed
}

/// Shared maximum content height for bubble content (text/image).
/// When text exceeds this height the chat shows an Expand quick action.
let KdBubbleMaxContentHeight: CGFloat = 200

// MARK: - BubbleView

struct BubbleView<Content: View>: View {
    var text: String? = nil
    let direction: KdBubbleDirection
    var timestamp: String = ""
    var delivery: KdDeliveryState? = nil
    @ViewBuilder let content: () -> Content

    @Environment(\.kdColors) private var kd

    // MARK: - Derived tokens

    private var bgColor: Color {
        switch direction {
        case .incoming: return kd.bg2
        case .outgoing: return kd.accentBg
        }
    }

    private var deliveryLabel: String {
        switch delivery {
        case .sending:   return " · sending"
        case .sent:      return " · sent"
        case .delivered: return " · delivered"
        case .failed:    return " · failed"
        case nil:        return ""
        }
    }

    // MARK: - Body

    var body: some View {
        HStack(spacing: 0) {
            if direction == .outgoing { Spacer(minLength: 0) }

            VStack(alignment: .leading, spacing: 0) {
                // Content slot + text
                content()

                if let t = text {
                    Text(t)
                        .kdStyle(.body, color: kd.text, multiline: true)
                }

                Spacer(minLength: KdSpacing.s1)

                // Foot row: timestamp + delivery (right-aligned)
                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    Text(timestamp + deliveryLabel)
                        .kdStyle(.caption, color: kd.text3)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            // Fixed 300pt max bubble width. Left/right gravity is handled by the
            // surrounding HStack spacers (incoming → leading, outgoing → trailing).
            // NOTE: do not use containerRelativeFrame here — on macOS it resolves
            // against the window width, not the split-view detail pane, which let
            // bubbles slide left underneath the sidebar.
            .frame(maxWidth: 300, alignment: .leading)
            .background(bgColor)
            .clipShape(BubbleShape(direction: direction))

            if direction == .incoming { Spacer(minLength: 0) }
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - BubbleShape

/// Asymmetric-corner rectangle:
/// - Normal corners: KdRadii.lg (18 pt)
/// - Speaker corner (top-leading for incoming, top-trailing for outgoing): 6 pt
private struct BubbleShape: Shape {
    let direction: KdBubbleDirection

    func path(in rect: CGRect) -> Path {
        let bigR: CGFloat = KdRadii.lg   // 18
        let smallR: CGFloat = KdRadii.xs // 6

        let topLeft  = direction == .incoming ? smallR : bigR
        let topRight = direction == .outgoing ? smallR : bigR
        let botLeft  = bigR
        let botRight = bigR

        var p = Path()
        p.move(to: CGPoint(x: rect.minX + topLeft, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - topRight, y: rect.minY))
        p.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY + topRight),
            control: CGPoint(x: rect.maxX, y: rect.minY)
        )
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - botRight))
        p.addQuadCurve(
            to: CGPoint(x: rect.maxX - botRight, y: rect.maxY),
            control: CGPoint(x: rect.maxX, y: rect.maxY)
        )
        p.addLine(to: CGPoint(x: rect.minX + botLeft, y: rect.maxY))
        p.addQuadCurve(
            to: CGPoint(x: rect.minX, y: rect.maxY - botLeft),
            control: CGPoint(x: rect.minX, y: rect.maxY)
        )
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + topLeft))
        p.addQuadCurve(
            to: CGPoint(x: rect.minX + topLeft, y: rect.minY),
            control: CGPoint(x: rect.minX, y: rect.minY)
        )
        p.closeSubpath()
        return p
    }
}

// MARK: - Convenience init (no content slot)

extension BubbleView where Content == EmptyView {
    init(
        text: String? = nil,
        direction: KdBubbleDirection,
        timestamp: String = "",
        delivery: KdDeliveryState? = nil
    ) {
        self.text = text
        self.direction = direction
        self.timestamp = timestamp
        self.delivery = delivery
        self.content = { EmptyView() }
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: KdSpacing.s3) {
        BubbleView(text: "Hello there!", direction: .incoming, timestamp: "10:32")
        BubbleView(text: "Hey! Sending a file now.", direction: .outgoing, timestamp: "10:33", delivery: .sent)
    }
    .padding()
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
