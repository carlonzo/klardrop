package com.carlom.klardrop.control

import java.io.File
import java.util.concurrent.TimeUnit

/** Same ffmpeg args/lookup as compose-ui's VideoThumbnail.desktopJvm.kt, writing to a file instead of stdout. */
internal actual fun extractVideoThumbnail(
  videoPath: String,
  outputPath: String,
  maxWidthPx: Int,
  timeoutMillis: Long,
): Boolean {
  val ffmpeg = findFfmpeg() ?: return false
  var process: Process? = null
  return try {
    process = ProcessBuilder(
      ffmpeg,
      "-y",
      "-loglevel", "error",
      // Seek before -i so ffmpeg jumps to the keyframe instead of decoding up to it.
      "-ss", "0",
      "-i", videoPath,
      "-frames:v", "1",
      // Downscale the long edge, keep the aspect ratio, and never upscale a small video.
      "-vf", "scale='min($maxWidthPx,iw)':-2",
      "-f", "image2",
      "-vcodec", "png",
      outputPath,
    )
      .redirectErrorStream(true)
      .start()

    // Drain stdout/stderr before waitFor: a full pipe buffer would deadlock the child.
    process.inputStream.use { it.readBytes() }
    val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    if (!finished) return false
    process.exitValue() == 0 && File(outputPath).isFile
  } catch (e: Exception) {
    false
  } finally {
    process?.takeIf { it.isAlive }?.destroyForcibly()
  }
}

/** Hard cap on the ffmpeg -version probe. */
private const val PROBE_TIMEOUT_SECONDS = 5L

private fun findFfmpeg(): String? {
  val candidates = listOf(
    // PATH first, then the usual package-manager locations — a bundled .app / .deb launch
    // often starts with a minimal PATH that omits /opt/homebrew and /usr/local.
    "ffmpeg",
    "/opt/homebrew/bin/ffmpeg",
    "/usr/local/bin/ffmpeg",
    "/usr/bin/ffmpeg",
  )
  return candidates.firstOrNull { candidate ->
    runCatching {
      val probe = ProcessBuilder(candidate, "-version").redirectErrorStream(true).start()
      probe.inputStream.use { it.readBytes() }
      val exited = probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!exited) probe.destroyForcibly()
      exited && probe.exitValue() == 0
    }.getOrDefault(false)
  }
}
