package com.carlom.klardrop.common.trust

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Unified Base64 encoding/decoding utilities for all platforms.
 * Replaces platform-specific encoding implementations to ensure consistency.
 */

/**
 * Encode ByteArray to Base64 string.
 * @return Base64 encoded string
 */
@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64String(): String = Base64.encode(this)

/**
 * Decode Base64 string to ByteArray.
 * @return Decoded byte array
 * @throws IllegalArgumentException if the string is not valid Base64
 */
@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64(): ByteArray = Base64.decode(this)

/**
 * Safe Base64 decoding that returns null instead of throwing exception.
 * @return Decoded byte array or null if decoding fails
 */
@OptIn(ExperimentalEncodingApi::class)
fun String.fromBase64OrNull(): ByteArray? = try {
    Base64.decode(this)
} catch (e: IllegalArgumentException) {
    null
}