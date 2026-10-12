package com.carlom.klardrop.control

/**
 * The native macOS app has no QR encoder, and cannot grow one by shelling out.
 *
 * The Linux engine renders `/qr-share` by running the system `qrencode` because qrcode-kotlin
 * — the encoder the JVM host uses — publishes no `linuxX64`/`linuxArm64` artifact. macOS has
 * the same missing artifact *and* an extra, harder constraint: the shipped app is sandboxed
 * (`com.apple.security.app-sandbox`), and a sandboxed process may only execute code inside its
 * own bundle. `NSTask`/`posix_spawn` of `/opt/homebrew/bin/qrencode` is denied by the kernel
 * before it ever reaches `execve`, so the Linux approach would be code that can only ever fail
 * at runtime.
 *
 * The remaining route — CIImage's `CIQRCodeGenerator` — is real, but it cannot be compiled or
 * exercised on the Linux host this work is developed on, and shipping unverifiable CoreImage
 * bindings into the one framework every macOS user runs is a worse trade than an honest
 * capability gap. So this throws, and [platformUnavailableCapabilities] keeps `qr-share` out of
 * the advertised list: a client (the Rust TUI included) sees the capability missing and labels
 * the feature unavailable, rather than pressing a button that 500s.
 */
actual fun renderQrMatrix(text: String): List<String> =
  throw UnsupportedOperationException(
    "QR rendering is not available on the native macOS host: the app is sandboxed and cannot " +
      "run qrencode, and no qrcode-kotlin macOS artifact exists",
  )