package com.carlom.klardrop.common.trust

import java.math.BigInteger
import java.security.interfaces.ECPublicKey

internal const val P256_COMPONENT_SIZE = 32
internal const val UNCOMPRESSED_PUBLIC_KEY_SIZE = 65

/**
 * Encode a JCA P-256 [ECPublicKey] as the 65-byte uncompressed SEC1 form
 * `0x04 || X || Y` used by [TrustCrypto] and on the wire.
 */
internal fun ECPublicKey.toRawUncompressed(): ByteArray {
    val x = w.affineX.toUnsignedFixedSize(P256_COMPONENT_SIZE)
    val y = w.affineY.toUnsignedFixedSize(P256_COMPONENT_SIZE)
    val out = ByteArray(UNCOMPRESSED_PUBLIC_KEY_SIZE)
    out[0] = 0x04
    System.arraycopy(x, 0, out, 1, P256_COMPONENT_SIZE)
    System.arraycopy(y, 0, out, 1 + P256_COMPONENT_SIZE, P256_COMPONENT_SIZE)
    return out
}

private fun BigInteger.toUnsignedFixedSize(size: Int): ByteArray {
    val raw = toByteArray()
    return when {
        raw.size == size -> raw
        raw.size == size + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
        raw.size < size -> ByteArray(size - raw.size) + raw
        else -> error("BigInteger does not fit into $size bytes (was ${raw.size})")
    }
}
