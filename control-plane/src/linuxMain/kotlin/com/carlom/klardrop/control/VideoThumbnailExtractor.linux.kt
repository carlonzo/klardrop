package com.carlom.klardrop.control

import com.carlom.klardrop.common.utils.execProcess
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Same ffmpeg args as the desktop JVM extractor, run via the shared posix_spawnp helper. */
internal actual fun extractVideoThumbnail(
  videoPath: String,
  outputPath: String,
  maxWidthPx: Int,
  timeoutMillis: Long,
): Boolean {
  val argv = listOf(
    "ffmpeg",
    "-y",
    "-loglevel", "error",
    "-ss", "0",
    "-i", videoPath,
    "-frames:v", "1",
    "-vf", "scale='min($maxWidthPx,iw)':-2",
    "-f", "image2",
    "-vcodec", "png",
    outputPath,
  )
  return try {
    val result = execProcess(argv, timeoutMillis = timeoutMillis, captureOutput = false)
    result.exitCode == 0 && SystemFileSystem.exists(Path(outputPath))
  } catch (e: Exception) {
    false
  }
}
