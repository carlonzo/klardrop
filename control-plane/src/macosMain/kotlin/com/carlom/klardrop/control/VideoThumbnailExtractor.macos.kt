package com.carlom.klardrop.control

/**
 * No video thumbnails on the native macOS host.
 *
 * `ffmpeg` is an external binary, so the same sandbox rule that stops the QR route applies:
 * a sandboxed process cannot execute it. Extracting the frame with AVFoundation is the obvious
 * alternative, but `/thumbnail` is not an advertised capability (`PRODUCTION_CAPABILITIES`
 * never listed it) and nothing in the native macOS app or the Rust client calls it — it exists
 * for the Qt and Omarchy frontends, which run against a JVM or Linux host that does extract
 * frames. Returning false keeps this contract honest without shipping untested AVFoundation
 * code for a route nothing on this platform reads.
 */
internal actual fun extractVideoThumbnail(
  videoPath: String,
  outputPath: String,
  maxWidthPx: Int,
  timeoutMillis: Long,
): Boolean = false