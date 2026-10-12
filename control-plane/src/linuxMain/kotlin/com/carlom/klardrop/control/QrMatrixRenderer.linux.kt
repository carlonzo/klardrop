package com.carlom.klardrop.control

import com.carlom.klardrop.common.utils.execProcess

// qrcode-kotlin (io.github.g0dkar:qrcode-kotlin, already used by compose-ui) has no linuxX64
// target in its published Gradle module metadata, so the native daemon shells out to the
// system `qrencode` (Omarchy ships /usr/bin/qrencode) instead of vendoring a QR encoder.
// `-t ASCII -m 0` (no quiet zone, we add that on the QML side) renders each module as exactly
// two characters ("##" dark, "  " light), which is what makes the two-char group -> '1'/'0'
// mapping below exact rather than a lossy ASCII-art approximation.
actual fun renderQrMatrix(text: String): List<String> {
  val result = execProcess(listOf("qrencode", "-t", "ASCII", "-m", "0", "-o", "-", text))
  check(result.exitCode == 0) { "qrencode failed (exit ${result.exitCode}): ${result.stderrString}" }
  return result.stdoutString
    .split('\n')
    .filter { it.isNotEmpty() }
    .map { line -> line.chunked(2).joinToString("") { if (it == "##") "1" else "0" } }
}
