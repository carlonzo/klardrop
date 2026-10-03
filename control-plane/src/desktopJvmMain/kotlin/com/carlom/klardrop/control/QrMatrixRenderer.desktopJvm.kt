package com.carlom.klardrop.control

import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

// Same library/settings as compose-ui's QrCodeImage (QrShareSheet.kt) so the daemon's HTTP
// response matches what the in-app Compose UI would have shown.
actual fun renderQrMatrix(text: String): List<String> {
  val matrix = QRCodeProcessor(text, ErrorCorrectionLevel.MEDIUM).encode()
  return matrix.map { row -> row.joinToString("") { if (it.dark) "1" else "0" } }
}
