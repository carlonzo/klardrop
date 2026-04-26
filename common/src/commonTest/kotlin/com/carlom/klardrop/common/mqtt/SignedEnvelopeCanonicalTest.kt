package com.carlom.klardrop.common.mqtt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The canonical-bytes layout is the contract sender and receiver must agree
 * on byte-for-byte. These tests pin the layout so a refactor can't silently
 * change it (any change would break every previously-signed envelope).
 */
class SignedEnvelopeCanonicalTest {

    @Test
    fun bytes_to_sign_matches_pinned_layout() {
        // sender="ab" (0x61 0x62), receiver="cd", ts=0x0102030405060708,
        // nonce=[0x09 0x0a], payload=[0x0b]
        val out = SignedEnvelopeCanonical.bytesToSign(
            senderDeviceId = "ab",
            receiverDeviceId = "cd",
            timestampMs = 0x0102030405060708L,
            nonce = byteArrayOf(0x09, 0x0a),
            payload = byteArrayOf(0x0b)
        )

        // [0,0,0,2] "ab" [0,0,0,2] "cd" [0x01..0x08] [0,0,0,2] [0x09,0x0a] [0,0,0,1] [0x0b]
        val expected = byteArrayOf(
            0, 0, 0, 2, 'a'.code.toByte(), 'b'.code.toByte(),
            0, 0, 0, 2, 'c'.code.toByte(), 'd'.code.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0, 0, 0, 2, 0x09, 0x0a,
            0, 0, 0, 1, 0x0b
        )
        assertTrue(out.contentEquals(expected), "canonical bytes drifted: ${out.toList()}")
    }

    @Test
    fun changing_any_field_changes_the_canonical_bytes() {
        val base = SignedEnvelopeCanonical.bytesToSign("a", "b", 1, byteArrayOf(1), byteArrayOf(2))

        val variants = listOf(
            SignedEnvelopeCanonical.bytesToSign("X", "b", 1, byteArrayOf(1), byteArrayOf(2)),
            SignedEnvelopeCanonical.bytesToSign("a", "Y", 1, byteArrayOf(1), byteArrayOf(2)),
            SignedEnvelopeCanonical.bytesToSign("a", "b", 2, byteArrayOf(1), byteArrayOf(2)),
            SignedEnvelopeCanonical.bytesToSign("a", "b", 1, byteArrayOf(9), byteArrayOf(2)),
            SignedEnvelopeCanonical.bytesToSign("a", "b", 1, byteArrayOf(1), byteArrayOf(9))
        )
        for (v in variants) {
            assertFalse(base.contentEquals(v), "canonical bytes did not change when a field changed")
        }
    }
}
