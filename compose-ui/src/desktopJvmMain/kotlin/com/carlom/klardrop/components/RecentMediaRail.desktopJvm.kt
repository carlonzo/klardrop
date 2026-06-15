package com.carlom.klardrop.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.vinceglb.filekit.PlatformFile

// Desktop never shows the recent-media rail — the attachment chooser isn't used
// on desktop at all (it keeps the direct file picker). No-op actual so the
// shared chooser composable still compiles for the desktop target.
@Composable
actual fun RecentMediaRail(
    onPick: (PlatformFile) -> Unit,
    modifier: Modifier,
) {
}
