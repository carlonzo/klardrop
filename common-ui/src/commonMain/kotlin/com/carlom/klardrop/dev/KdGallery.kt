package com.carlom.klardrop.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.components.Banner
import com.carlom.klardrop.components.Bubble
import com.carlom.klardrop.components.ChatHeader
import com.carlom.klardrop.components.DateChip
import com.carlom.klardrop.components.DeviceAvatar
import com.carlom.klardrop.components.DeviceRow
import com.carlom.klardrop.components.FileCard
import com.carlom.klardrop.components.IncomingTransferCard
import com.carlom.klardrop.components.KdAvatarStyle
import com.carlom.klardrop.components.KdBannerTone
import com.carlom.klardrop.components.KdBubbleDirection
import com.carlom.klardrop.components.KdDeliveryState
import com.carlom.klardrop.components.KdDeviceKind
import com.carlom.klardrop.components.KdFileState
import com.carlom.klardrop.components.KdPermissionItem
import com.carlom.klardrop.components.KdRowState
import com.carlom.klardrop.components.KdShareDevice
import com.carlom.klardrop.components.KdStatus
import com.carlom.klardrop.components.KdVisibilityState
import com.carlom.klardrop.components.MessageInput
import com.carlom.klardrop.components.PairingDialog
import com.carlom.klardrop.components.PermissionsChecklist
import com.carlom.klardrop.components.SectionHead
import com.carlom.klardrop.components.ShareSheet
import com.carlom.klardrop.components.Sidebar
import com.carlom.klardrop.components.StatusDot
import com.carlom.klardrop.components.VisibilityPill
import com.carlom.klardrop.theme.AppTheme
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// KdGallery — dev-only visual fixture showing all C01–C16 states
// Not wired into the running app. Drop KdGalleryPreview into KlardropApp for
// visual debugging.
// ---------------------------------------------------------------------------

@Composable
fun KdGallery() {
    AppTheme(useDarkTheme = true) {
        val colors = KdTheme.colors
        val typography = KdTheme.typography
        val spacing = KdTheme.spacing

        Column(
            modifier = Modifier
                .background(colors.bg0)
                .verticalScroll(rememberScrollState())
                .padding(spacing.s4),
            verticalArrangement = Arrangement.spacedBy(spacing.s6),
        ) {

            // ── C01 DeviceAvatar ─────────────────────────────────────────
            GallerySection("C01 · DeviceAvatar") {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
                    KdDeviceKind.values().forEach { kind ->
                        DeviceAvatar(kind = kind, style = KdAvatarStyle.Neutral, size = 48.dp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
                    listOf(32.dp, 36.dp, 40.dp, 48.dp, 64.dp, 84.dp).forEach { size ->
                        DeviceAvatar(
                            kind = KdDeviceKind.Mac,
                            style = KdAvatarStyle.Tinted,
                            status = KdStatus.Ok,
                            size = size,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
                    KdStatus.values().forEach { status ->
                        DeviceAvatar(
                            kind = KdDeviceKind.Android,
                            style = KdAvatarStyle.Neutral,
                            status = status,
                            size = 48.dp,
                        )
                    }
                }
            }

            // ── C02 DeviceRow ────────────────────────────────────────────
            GallerySection("C02 · DeviceRow") {
                KdRowState.values().forEach { state ->
                    DeviceRow(
                        name = "Carlo's MacBook Pro",
                        subText = state.name,
                        kind = KdDeviceKind.Mac,
                        avatarStyle = KdAvatarStyle.Tinted,
                        rowState = state,
                        status = KdStatus.Ok,
                    )
                }
            }

            // ── C03 StatusDot ────────────────────────────────────────────
            GallerySection("C03 · StatusDot") {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.s4)) {
                    KdStatus.values().forEach { status ->
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            StatusDot(status = status)
                            Spacer(Modifier.height(spacing.s1))
                            Text(status.name, style = typography.caption.copy(color = colors.text3))
                        }
                    }
                }
            }

            // ── C04 VisibilityPill ───────────────────────────────────────
            GallerySection("C04 · VisibilityPill") {
                VisibilityPill(state = KdVisibilityState.Visible("Home Network"))
                VisibilityPill(state = KdVisibilityState.Hidden)
            }

            // ── C05 SectionHead ──────────────────────────────────────────
            GallerySection("C05 · SectionHead") {
                SectionHead(label = "Your Devices", count = 3)
                SectionHead(label = "Nearby", count = 0)
                SectionHead(
                    label = "Your Devices",
                    count = 2,
                    trailing = {
                        Text(
                            "Edit",
                            style = typography.body.copy(color = colors.accent),
                        )
                    },
                )
            }

            // ── C06 Bubble ───────────────────────────────────────────────
            GallerySection("C06 · Bubble") {
                Bubble(
                    text = "Hey! Can you send me that file?",
                    direction = KdBubbleDirection.In,
                    timestamp = "10:30",
                )
                Bubble(
                    text = "Sure, sending it now!",
                    direction = KdBubbleDirection.Out,
                    timestamp = "10:31",
                    delivery = KdDeliveryState.Delivered,
                )
                Bubble(
                    text = "Really long message text that goes on and on to test the max-width truncation behaviour at 78% of available thread width.",
                    direction = KdBubbleDirection.In,
                    timestamp = "10:32",
                )
            }

            // ── C07 FileCard ─────────────────────────────────────────────
            GallerySection("C07 · FileCard") {
                FileCard(
                    fileName = "PXL_20260508_180229.mp4",
                    fileSize = "142 MB",
                    state = KdFileState.Sending(0.45f),
                )
                FileCard(
                    fileName = "presentation.pdf",
                    fileSize = "8.3 MB",
                    state = KdFileState.Receiving(0.80f),
                )
                FileCard(
                    fileName = "design-assets.zip",
                    fileSize = "220 MB",
                    state = KdFileState.Done,
                )
                FileCard(
                    fileName = "backup.tar.gz",
                    fileSize = "1.2 GB",
                    state = KdFileState.Failed,
                )
            }

            // ── C08 Banner ───────────────────────────────────────────────
            GallerySection("C08 · Banner") {
                Banner(tone = KdBannerTone.Ok, title = "File received", body = "design-assets.zip · 220 MB")
                Banner(tone = KdBannerTone.Warn, title = "Permissions needed", body = "Allow Local Network access to discover devices")
                Banner(tone = KdBannerTone.Err, title = "Device offline", body = "Carlo's iPhone is no longer reachable")
            }

            // ── C09 MessageInput ─────────────────────────────────────────
            GallerySection("C09 · MessageInput") {
                var text1 by remember { mutableStateOf("") }
                MessageInput(value = text1, onValueChange = { text1 = it }, onSend = {})

                var text2 by remember { mutableStateOf("Hello!") }
                MessageInput(value = text2, onValueChange = { text2 = it }, onSend = {}, enabled = false)

                var text3 by remember { mutableStateOf("") }
                MessageInput(value = text3, onValueChange = { text3 = it }, onSend = {}, desktopVariant = true)
            }

            // ── C10 IncomingTransferCard ─────────────────────────────────
            GallerySection("C10 · IncomingTransferCard") {
                IncomingTransferCard(
                    senderName = "Unknown iPhone",
                    fileName = "PXL_20260508_180229.mp4",
                    fileSize = "142 MB",
                    onAccept = {},
                    onDecline = {},
                )
            }

            // ── C11 ShareSheet ───────────────────────────────────────────
            GallerySection("C11 · ShareSheet") {
                var selectedId by remember { mutableStateOf<String?>("1") }
                ShareSheet(
                    trustedDevices = listOf(
                        KdShareDevice("1", "Carlo's Mac", KdDeviceKind.Mac, true, KdStatus.Ok),
                        KdShareDevice("2", "Carlo's iPhone", KdDeviceKind.Iphone, true, KdStatus.Ok),
                    ),
                    nearbyDevices = listOf(
                        KdShareDevice("3", "Unknown Android", KdDeviceKind.Android, false),
                    ),
                    selectedId = selectedId,
                    onSelectDevice = { selectedId = it.id },
                    onSend = {},
                )
            }

            // ── C12 PairingDialog ────────────────────────────────────────
            GallerySection("C12 · PairingDialog") {
                PairingDialog(
                    localDeviceName = "My MacBook Pro",
                    remoteDeviceName = "Unknown iPhone",
                    localKind = KdDeviceKind.Mac,
                    remoteKind = KdDeviceKind.Iphone,
                    verificationCode = "4829",
                    onCancel = {},
                    onConfirm = {},
                )
            }

            // ── C13 PermissionsChecklist ─────────────────────────────────
            GallerySection("C13 · PermissionsChecklist") {
                PermissionsChecklist(
                    items = listOf(
                        KdPermissionItem("net", "Local Network", "Required to discover nearby devices", false),
                        KdPermissionItem("notif", "Notifications", "Alerts for incoming transfers", false),
                        KdPermissionItem("bg", "Background App Refresh", "Keeps devices discoverable", true),
                    ),
                )
            }

            // ── C14 ChatHeader ───────────────────────────────────────────
            GallerySection("C14 · ChatHeader") {
                ChatHeader(
                    deviceName = "Carlo's iPhone 16 Pro Max",
                    subText = "Reachable",
                    kind = KdDeviceKind.Iphone,
                    avatarStyle = KdAvatarStyle.Tinted,
                    status = KdStatus.Ok,
                    isReachable = true,
                )
                ChatHeader(
                    deviceName = "Carlo's iPhone",
                    subText = "Offline",
                    kind = KdDeviceKind.Iphone,
                    avatarStyle = KdAvatarStyle.Tinted,
                    status = KdStatus.Err,
                    isReachable = false,
                )
                ChatHeader(
                    deviceName = "Desktop Toolbar Variant",
                    subText = "Reachable",
                    kind = KdDeviceKind.Pc,
                    status = KdStatus.Ok,
                    toolbarVariant = true,
                    isReachable = true,
                )
            }

            // ── C15 DateChip ─────────────────────────────────────────────
            GallerySection("C15 · DateChip") {
                DateChip(label = "Today")
                DateChip(label = "Monday, May 5")
            }

            // ── C16 Sidebar ──────────────────────────────────────────────
            GallerySection("C16 · Sidebar") {
                Sidebar(
                    width = 300.dp,
                    visibilityState = KdVisibilityState.Visible("Home"),
                    yoursSection = {
                        SectionHead(label = "Your Devices", count = 2)
                        DeviceRow(
                            name = "Carlo's iPhone",
                            kind = KdDeviceKind.Iphone,
                            avatarStyle = KdAvatarStyle.Tinted,
                            status = KdStatus.Ok,
                            rowState = KdRowState.Active,
                        )
                        DeviceRow(
                            name = "Carlo's iPad",
                            kind = KdDeviceKind.Tablet,
                            avatarStyle = KdAvatarStyle.Tinted,
                            status = KdStatus.Ok,
                        )
                    },
                    nearbySection = {
                        SectionHead(label = "Nearby", count = 1)
                        DeviceRow(
                            name = "Unknown Android",
                            kind = KdDeviceKind.Android,
                            avatarStyle = KdAvatarStyle.Neutral,
                        )
                    },
                    footer = {
                        Text(
                            text = "Carlo's MacBook Pro",
                            style = KdTheme.typography.caption.copy(color = KdTheme.colors.text2),
                        )
                    },
                    modifier = Modifier.height(400.dp),
                )
            }

            Spacer(Modifier.height(spacing.s9))
        }
    }
}

@Composable
fun KdGalleryPreview() {
    KdGallery()
}

@Composable
private fun GallerySection(
    title: String,
    content: @Composable () -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
        Text(
            text = title,
            style = typography.overline.copy(color = colors.accent),
        )
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
            content()
        }
    }
}
