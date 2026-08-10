package com.carlom.klardrop.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android video thumbnails via [MediaMetadataRetriever] — the only API that reads a frame
 * out of both a plain file path and a MediaStore `content://` URI (which is how received
 * videos are stored, so it's the case that matters for the chat).
 *
 * Coil has no video decoder registered here, and its image decoders simply fail on a video
 * container, which is why a received video used to show no preview at all.
 */
@Composable
actual fun rememberVideoThumbnail(path: String): ImageBitmap? {
  val context = LocalContext.current
  var thumbnail by remember(path) { mutableStateOf(VideoThumbnailCache.get(path)) }

  LaunchedEffect(path) {
    if (thumbnail != null) return@LaunchedEffect
    // Decoding opens and seeks the media file: always off the main thread.
    val decoded = withContext(Dispatchers.IO) { decodeFirstFrame(context, path) }
    if (decoded != null) {
      VideoThumbnailCache.put(path, decoded)
      thumbnail = decoded
    }
  }

  return thumbnail
}

/** Longest edge of the decoded frame — enough for a chat bubble, small enough to keep. */
private const val MAX_THUMBNAIL_PIXELS = 640

private fun decodeFirstFrame(context: Context, path: String): ImageBitmap? {
  val retriever = MediaMetadataRetriever()
  return try {
    val normalized = normalizeMediaPath(path)
    if (normalized.startsWith("content://")) {
      retriever.setDataSource(context, Uri.parse(normalized))
    } else {
      retriever.setDataSource(normalized)
    }

    // OPTION_CLOSEST_SYNC at t=0 is the cheapest frame to get: no decoding up to an
    // arbitrary timestamp, just the first keyframe.
    val frame: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      retriever.getScaledFrameAtTime(
        0L,
        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        MAX_THUMBNAIL_PIXELS,
        MAX_THUMBNAIL_PIXELS,
      )
    } else {
      retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }
    frame?.asImageBitmap()
  } catch (e: Exception) {
    // Unsupported codec, deleted file, revoked URI permission — the bubble falls back to
    // the play-badge placeholder, so this is informational only.
    log("VideoThumbnail", "Failed to decode video frame for $path: ${e.message}")
    null
  } finally {
    runCatching { retriever.release() }
  }
}
