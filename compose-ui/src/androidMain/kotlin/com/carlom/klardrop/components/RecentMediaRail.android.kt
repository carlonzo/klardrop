package com.carlom.klardrop.components

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.media.rememberVideoThumbnail
import com.carlom.klardrop.theme.KdTheme
import coil3.compose.AsyncImage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MediaEntry(val uri: Uri, val isVideo: Boolean) {
    val model: String get() = uri.toString()
}

private const val RECENT_MEDIA_LIMIT = 40
private val ThumbSize = 76.dp

@Composable
actual fun RecentMediaRail(
    onPick: (PlatformFile) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val permissions = remember { mediaPermissions() }
    var granted by remember { mutableStateOf(hasMediaAccess(context, permissions)) }
    var items by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }

    val launcher = rememberLauncherForActivityResult(RequestMultiplePermissions()) {
        granted = hasMediaAccess(context, permissions)
    }

    // Ask for the read-media permission the first time the rail appears. Once
    // granted (or already granted) load the most recent items off the main thread.
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(permissions)
    }
    LaunchedEffect(granted) {
        if (granted) items = withContext(Dispatchers.IO) { queryRecentMedia(context) }
    }

    // Nothing to show: permission denied, or the gallery is empty. The chooser
    // still offers Gallery/Files/Paste, so we just render no rail.
    if (!granted || items.isEmpty()) return

    val radii = KdTheme.radii
    val colors = KdTheme.colors
    val spacing = KdTheme.spacing

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        contentPadding = PaddingValues(horizontal = spacing.s3),
    ) {
        items(items, key = { it.model }) { entry ->
            Box(
                modifier = Modifier
                    .size(ThumbSize)
                    .clip(radii.shapeSm)
                    .background(colors.bg2)
                    .clickable { onPick(PlatformFile(entry.uri)) },
            ) {
                if (entry.isVideo) {
                    // Coil has no video decoder, so a video URI decodes to nothing — pull the
                    // first frame through MediaMetadataRetriever instead (same path the chat
                    // bubble's video preview uses). Null while decoding: the tile's background
                    // plus the play badge below still read as "video".
                    val frame = rememberVideoThumbnail(entry.model)
                    if (frame != null) {
                        Image(
                            bitmap = frame,
                            contentDescription = "Recent video",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(ThumbSize),
                        )
                    }
                } else {
                    AsyncImage(
                        model = entry.model,
                        contentDescription = "Recent photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(ThumbSize),
                    )
                }
                if (entry.isVideo) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = colors.textInv,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(spacing.s1)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

// Full or partial (Android 14 "selected photos") access both let us read media.
private fun hasMediaAccess(context: Context, permissions: Array<String>): Boolean =
    permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun queryRecentMedia(context: Context): List<MediaEntry> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
    val args = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

    val result = ArrayList<MediaEntry>(RECENT_MEDIA_LIMIT)
    runCatching {
        context.contentResolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext() && result.size < RECENT_MEDIA_LIMIT) {
                val id = cursor.getLong(idColumn)
                val isVideo = cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                result += MediaEntry(ContentUris.withAppendedId(collection, id), isVideo)
            }
        }
    }
    return result
}
