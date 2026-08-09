package com.carlom.klardrop.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

/**
 * First-frame thumbnail for the video stored at [path], or null when none is available —
 * still decoding, the file is unreadable, or the platform has no video decoder at all.
 *
 * [path] is a persisted `file_transfers.file_path`: a filesystem path on desktop, and on
 * Android usually a MediaStore `content://` URI (received media is inserted into the
 * gallery, so it has no filesystem path and no extension). Implementations normalise it
 * with [normalizeMediaPath].
 *
 * Decoding happens off the main thread and results are cached in [VideoThumbnailCache], so
 * scrolling a chat back and forth doesn't re-decode the same video.
 */
@Composable
expect fun rememberVideoThumbnail(path: String): ImageBitmap?

/**
 * Inline video preview for a chat bubble: the decoded first frame with a play badge over
 * it, exactly like the image preview next door (and like the iOS chat row).
 *
 * While the frame is decoding — or on a platform that can't decode video at all — the badge
 * is drawn on a neutral tile instead. The bubble is never empty: before this existed, a
 * received video rendered as a file card with no visual at all, which read as "the
 * thumbnail is broken".
 */
@Composable
fun VideoPreview(
  path: String,
  contentDescription: String?,
  maxHeight: Dp,
  modifier: Modifier = Modifier,
  maxWidth: Dp = 320.dp,
) {
  val thumbnail = rememberVideoThumbnail(path)
  val colors = KdTheme.colors

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    if (thumbnail != null) {
      Image(
        bitmap = thumbnail,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .widthIn(max = maxWidth)
          .heightIn(max = maxHeight)
          .clip(RoundedCornerShape(10.dp)),
      )
    } else {
      Box(
        modifier = Modifier
          .size(width = PlaceholderWidth, height = PlaceholderHeight)
          .clip(RoundedCornerShape(10.dp))
          .background(colors.bg2),
      )
    }

    Icon(
      imageVector = Icons.Default.PlayArrow,
      contentDescription = null,
      tint = colors.textInv,
      modifier = Modifier
        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        .padding(6.dp)
        .size(28.dp),
    )
  }
}

private val PlaceholderWidth = 220.dp
private val PlaceholderHeight = 132.dp

/**
 * Decoded frames keyed by the path they came from. Small and process-lifetime: a handful of
 * bitmaps is cheap next to re-running a video decoder on every recomposition, and entries
 * are evicted oldest-first past [MAX_ENTRIES].
 *
 * Only touched from the composition (main thread) — the platform implementations decode on a
 * background dispatcher but read and write the cache on the caller's thread.
 */
internal object VideoThumbnailCache {

  private const val MAX_ENTRIES = 24

  private val entries = LinkedHashMap<String, ImageBitmap>()

  fun get(path: String): ImageBitmap? = entries[path]

  fun put(path: String, bitmap: ImageBitmap) {
    entries.remove(path)
    entries[path] = bitmap
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }
}

/**
 * Normalises a persisted file location into something a platform media API can open.
 *
 * MediaStore URIs round-tripped through kotlinx.io `Path` come back with their
 * "content://" separator collapsed to a single slash ("content:/media/..."), so restore it;
 * a `file://` prefix is stripped back to a plain path. Mirrors `toImageModel` in the chat
 * screen, which does the same normalisation for Coil.
 */
internal fun normalizeMediaPath(path: String): String = when {
  path.startsWith("content://") -> path
  path.startsWith("content:/") -> "content://" + path.removePrefix("content:/")
  path.startsWith("file://") -> path.removePrefix("file://")
  else -> path
}
