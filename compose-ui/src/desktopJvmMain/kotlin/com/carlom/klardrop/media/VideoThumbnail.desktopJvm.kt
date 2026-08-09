package com.carlom.klardrop.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Desktop video thumbnails.
 *
 * The JVM has no video decoder of its own and Skia only decodes still images, so the frame
 * has to come from outside: if an `ffmpeg` binary is on PATH we ask it for the first frame
 * as a PNG on stdout. When it isn't installed (or fails) this returns null and the caller
 * draws its play-badge placeholder — the app never depends on ffmpeg being present, it just
 * renders a nicer preview when it is.
 */
@Composable
actual fun rememberVideoThumbnail(path: String): ImageBitmap? {
  var thumbnail by remember(path) { mutableStateOf(VideoThumbnailCache.get(path)) }

  LaunchedEffect(path) {
    if (thumbnail != null) return@LaunchedEffect
    val decoded = withContext(Dispatchers.IO) { extractFirstFrame(path) }
    if (decoded != null) {
      VideoThumbnailCache.put(path, decoded)
      thumbnail = decoded
    }
  }

  return thumbnail
}

/** Longest edge of the extracted frame — enough for a chat bubble, cheap to hold in memory. */
private const val MAX_THUMBNAIL_PIXELS = 640

/** Hard cap on the ffmpeg call so a malformed file can't leave a process hanging around. */
private const val EXTRACT_TIMEOUT_SECONDS = 5L

/** Resolved once per process: null when there is no usable ffmpeg on this machine. */
private val ffmpegBinary: String? by lazy { findFfmpeg() }

private fun extractFirstFrame(path: String): ImageBitmap? {
  val ffmpeg = ffmpegBinary ?: return null
  val file = File(normalizeMediaPath(path))
  if (!file.isFile) return null

  var process: Process? = null
  return try {
    process = ProcessBuilder(
      ffmpeg,
      "-loglevel", "error",
      // Seek before -i so ffmpeg jumps to the keyframe instead of decoding up to it.
      "-ss", "0",
      "-i", file.absolutePath,
      "-frames:v", "1",
      // Downscale the long edge, keep the aspect ratio, and never upscale a small video.
      "-vf", "scale='min($MAX_THUMBNAIL_PIXELS,iw)':-2",
      "-f", "image2",
      "-vcodec", "png",
      "-",
    )
      .redirectErrorStream(false)
      .start()

    // Read stdout before waitFor: a full pipe buffer would deadlock the child.
    val png = process.inputStream.use { it.readBytes() }
    val finished = process.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      log("VideoThumbnail", "ffmpeg timed out extracting a frame from ${file.name}")
      return null
    }
    if (process.exitValue() != 0 || png.isEmpty()) return null

    SkiaImage.makeFromEncoded(png).toComposeImageBitmap()
  } catch (e: Exception) {
    log("VideoThumbnail", "Failed to extract video frame for $path: ${e.message}")
    null
  } finally {
    process?.takeIf { it.isAlive }?.destroyForcibly()
  }
}

private fun findFfmpeg(): String? {
  val candidates = buildList {
    // PATH first, then the usual package-manager locations — a bundled .app / .deb launch
    // often starts with a minimal PATH that omits /opt/homebrew and /usr/local.
    add("ffmpeg")
    add("/opt/homebrew/bin/ffmpeg")
    add("/usr/local/bin/ffmpeg")
    add("/usr/bin/ffmpeg")
  }
  return candidates.firstOrNull { candidate ->
    runCatching {
      val probe = ProcessBuilder(candidate, "-version")
        .redirectErrorStream(true)
        .start()
      // Drain before waiting: an unread pipe can block the child on its own banner output.
      probe.inputStream.use { it.readBytes() }
      val exited = probe.waitFor(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!exited) probe.destroyForcibly()
      exited && probe.exitValue() == 0
    }.getOrDefault(false)
  }.also {
    if (it == null) log("VideoThumbnail", "No ffmpeg on this machine; video bubbles use the placeholder preview")
  }
}
