package com.carlom.klardrop.control

/**
 * Writes [videoPath]'s first frame as a PNG to [outputPath], scaled to max [maxWidthPx] wide,
 * via `ffmpeg` (argv, never a shell string). Returns true on success; false — never throws —
 * when ffmpeg isn't on PATH, times out after [timeoutMillis], or fails for any other reason.
 * Mirrors the args used by compose-ui's desktop VideoThumbnail extraction.
 */
internal expect fun extractVideoThumbnail(
  videoPath: String,
  outputPath: String,
  maxWidthPx: Int,
  timeoutMillis: Long,
): Boolean
