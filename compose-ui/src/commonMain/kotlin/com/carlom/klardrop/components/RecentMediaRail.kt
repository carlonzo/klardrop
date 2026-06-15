package com.carlom.klardrop.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.vinceglb.filekit.PlatformFile

/**
 * Horizontal rail of the user's most recent photos/videos for one-tap sending,
 * shown inside the attachment chooser.
 *
 * The implementation is platform-specific: only Android renders media (querying
 * MediaStore behind the READ_MEDIA permissions, which it requests on first
 * composition). Every other target renders nothing — desktop keeps the plain
 * file picker and iOS uses its own native SwiftUI rail.
 *
 * @param onPick invoked with the picked media wrapped as a FileKit [PlatformFile]
 */
@Composable
expect fun RecentMediaRail(
    onPick: (PlatformFile) -> Unit,
    modifier: Modifier = Modifier,
)
