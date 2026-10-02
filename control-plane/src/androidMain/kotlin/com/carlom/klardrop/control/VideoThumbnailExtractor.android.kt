package com.carlom.klardrop.control

// ponytail: no ffmpeg binary on Android; /thumbnail always falls back to the null-thumbnail
// response there. Add a MediaMetadataRetriever-based extractor if Android ever needs this.
internal actual fun extractVideoThumbnail(
  videoPath: String,
  outputPath: String,
  maxWidthPx: Int,
  timeoutMillis: Long,
): Boolean = false
