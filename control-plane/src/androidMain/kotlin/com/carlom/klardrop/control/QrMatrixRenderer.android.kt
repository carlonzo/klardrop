package com.carlom.klardrop.control

// ponytail: the loopback control plane's QR-share endpoint targets the Omarchy/Linux daemon
// (see linux/omarchy/TODO.md M4); the Android app builds its own QR image straight from
// QrShareSession.state via compose-ui's QrCodeImage, so this actual only exists to keep the
// android target compiling. Wire up qrcode-kotlin's android artifact here if an Android
// ControlPlane client ever needs the raw matrix too.
actual fun renderQrMatrix(text: String): List<String> =
  throw UnsupportedOperationException("QR matrix rendering is not wired up for the Android control-plane target")
