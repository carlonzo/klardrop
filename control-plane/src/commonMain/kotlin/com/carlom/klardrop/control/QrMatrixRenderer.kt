package com.carlom.klardrop.control

/**
 * Renders [text] (the QR-share URL) into a QR code matrix: one String per row, one char
 * ('1' = dark module, '0' = light module) per column. No quiet zone — the caller (the
 * `/qr-share` HTTP response, then the QML overlay) adds the white border.
 *
 * Throws on failure; ControlPlane's generic dispatch handler turns that into a 500 like any
 * other route error.
 */
expect fun renderQrMatrix(text: String): List<String>
