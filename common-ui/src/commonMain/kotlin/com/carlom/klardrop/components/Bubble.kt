package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C06 · Bubble
// ---------------------------------------------------------------------------

enum class KdBubbleDirection { In, Out }

enum class KdDeliveryState { Sending, Sent, Delivered, Failed }

/**
 * Shared cap on how tall a bubble's content may grow before it's clipped.
 *
 * Used by image previews and long text alike so every bubble tops out at the
 * same height — a single, very long message no longer dominates the thread.
 * When text exceeds this, the chat surfaces an "Expand" quick action that opens
 * the full message in a sheet/dialog.
 */
val KdBubbleMaxContentHeight = 200.dp

/**
 * Chat message bubble with asymmetric corner radius.
 *
 * Corner spec:
 * - Normal corners: r/lg (18 dp)
 * - Speaker corner (top-left for In, top-right for Out): 6 dp
 *
 * @param text          bubble message text
 * @param direction     In (incoming) or Out (outgoing)
 * @param timestamp     caption text for the foot row
 * @param delivery      optional delivery state shown in foot
 * @param content       optional custom content slot (used by FileCard embedded in bubble)
 * @param modifier      applied to the outermost BoxWithConstraints
 */
@Composable
fun Bubble(
    text: String? = null,
    direction: KdBubbleDirection,
    timestamp: String = "",
    delivery: KdDeliveryState? = null,
    content: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    val bigR = 18.dp
    val smallR = 6.dp

    val shape = when (direction) {
        KdBubbleDirection.In ->
            RoundedCornerShape(topStart = smallR, topEnd = bigR, bottomStart = bigR, bottomEnd = bigR)
        KdBubbleDirection.Out ->
            RoundedCornerShape(topStart = bigR, topEnd = smallR, bottomStart = bigR, bottomEnd = bigR)
    }

    val bgColor = when (direction) {
        KdBubbleDirection.In  -> colors.bg2
        KdBubbleDirection.Out -> colors.accentBg
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f

        Box(modifier = Modifier.align(
            if (direction == KdBubbleDirection.In) Alignment.TopStart else Alignment.TopEnd
        )) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(shape)
                    .background(bgColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    if (content != null) {
                        content()
                    }
                    if (text != null) {
                        Text(
                            text = text,
                            style = typography.body.copy(color = colors.text),
                        )
                    }
                    Spacer(Modifier.height(spacing.s1))
                    // Foot row: timestamp + delivery
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        val deliveryLabel = when (delivery) {
                            KdDeliveryState.Sending   -> " · sending"
                            KdDeliveryState.Sent      -> " · sent"
                            KdDeliveryState.Delivered -> " · delivered"
                            KdDeliveryState.Failed    -> " · failed"
                            null                      -> ""
                        }
                        Text(
                            text = timestamp + deliveryLabel,
                            style = typography.caption.copy(color = colors.text3),
                        )
                    }
                }
            }
        }
    }
}
