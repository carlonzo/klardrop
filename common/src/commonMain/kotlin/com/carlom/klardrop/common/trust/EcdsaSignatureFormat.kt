package com.carlom.klardrop.common.trust

/**
 * P-256 ECDSA signature format conversion.
 *
 * The wire format used throughout Klardrop is RAW: 64 bytes laid out as r‖s, each component
 * left-padded to 32 bytes. Platform secure-store APIs (Android Keystore JCA `Signature`,
 * Apple `SecKeyCreateSignature`) instead emit DER-encoded ASN.1 SEQUENCE { INTEGER r,
 * INTEGER s }. This object converts between the two so platform implementations of
 * [TrustStorage.signWithDeviceKey] can return wire-format-compatible bytes.
 */
internal object EcdsaSignatureFormat {

    private const val P256_COMPONENT_SIZE = 32
    private const val RAW_SIGNATURE_SIZE = 64
    private const val DER_SEQUENCE_TAG: Byte = 0x30
    private const val DER_INTEGER_TAG: Byte = 0x02

    fun derToRaw(der: ByteArray): ByteArray {
        var idx = 0
        require(idx < der.size && der[idx] == DER_SEQUENCE_TAG) { "Expected DER SEQUENCE tag" }
        idx++
        val seqLen = readLength(der, idx)
        idx = seqLen.next
        val end = idx + seqLen.value
        require(end <= der.size) { "Truncated DER signature" }

        require(idx < der.size && der[idx] == DER_INTEGER_TAG) { "Expected DER INTEGER tag for r" }
        idx++
        val rLen = readLength(der, idx)
        idx = rLen.next
        val r = der.copyOfRange(idx, idx + rLen.value)
        idx += rLen.value

        require(idx < der.size && der[idx] == DER_INTEGER_TAG) { "Expected DER INTEGER tag for s" }
        idx++
        val sLen = readLength(der, idx)
        idx = sLen.next
        val s = der.copyOfRange(idx, idx + sLen.value)

        return ByteArray(RAW_SIGNATURE_SIZE).also {
            copyComponent(r, it, 0)
            copyComponent(s, it, P256_COMPONENT_SIZE)
        }
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == RAW_SIGNATURE_SIZE) { "RAW signature must be $RAW_SIGNATURE_SIZE bytes, was ${raw.size}" }
        val r = encodeInteger(raw.copyOfRange(0, P256_COMPONENT_SIZE))
        val s = encodeInteger(raw.copyOfRange(P256_COMPONENT_SIZE, RAW_SIGNATURE_SIZE))
        val body = r + s
        return byteArrayOf(DER_SEQUENCE_TAG) + lengthBytes(body.size) + body
    }

    private data class LengthRead(val value: Int, val next: Int)

    private fun readLength(buf: ByteArray, offset: Int): LengthRead {
        require(offset < buf.size) { "Length byte missing" }
        val first = buf[offset].toInt() and 0xff
        if (first and 0x80 == 0) return LengthRead(first, offset + 1)
        val numBytes = first and 0x7f
        require(numBytes in 1..4) { "Unsupported DER length encoding" }
        var value = 0
        for (i in 0 until numBytes) {
            value = (value shl 8) or (buf[offset + 1 + i].toInt() and 0xff)
        }
        return LengthRead(value, offset + 1 + numBytes)
    }

    private fun copyComponent(component: ByteArray, dest: ByteArray, destOffset: Int) {
        var start = 0
        while (start < component.size - 1 && component[start] == 0.toByte()) start++
        val trimmedSize = component.size - start
        require(trimmedSize <= P256_COMPONENT_SIZE) { "ECDSA component too large for P-256" }
        val padding = P256_COMPONENT_SIZE - trimmedSize
        for (i in 0 until trimmedSize) {
            dest[destOffset + padding + i] = component[start + i]
        }
    }

    private fun encodeInteger(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte() && (bytes[start + 1].toInt() and 0x80) == 0) {
            start++
        }
        val trimmed = bytes.copyOfRange(start, bytes.size)
        val needsLeadingZero = (trimmed[0].toInt() and 0x80) != 0
        val body = if (needsLeadingZero) byteArrayOf(0) + trimmed else trimmed
        return byteArrayOf(DER_INTEGER_TAG) + lengthBytes(body.size) + body
    }

    private fun lengthBytes(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        val out = mutableListOf<Byte>()
        var n = len
        while (n != 0) {
            out.add(0, (n and 0xff).toByte())
            n = n ushr 8
        }
        return byteArrayOf((0x80 or out.size).toByte()) + out.toByteArray()
    }
}
