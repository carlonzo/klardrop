package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.communication.message.SimpleSendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.TrustedMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Repro for Bugsnag ClassCastException (6a4dee0c / 6a4de86e):
 *
 * Messenger wraps outbound text for trusted peers in a [TrustedMessage] envelope before
 * transport selection. Nearby Share only understands raw text bytes and used to cast
 * `request.message as TextMessage`, which threw when the peer was trusted and the only
 * available transport was Nearby — blocking the send entirely.
 *
 * Contract pinned here:
 *  - Plain [TextMessage] yields its text.
 *  - [TrustedMessage] (or any other envelope) fails with a clear IllegalArgumentException
 *    rather than ClassCastException. Messenger must pass the unwrapped application message
 *    into the Nearby path.
 */
class NearbyTextContentTest {

  @Test
  fun textMessage_returnsPlainText() {
    val request = SimpleSendMessageRequest(TextMessage(text = "hello trusted peer"))
    assertEquals("hello trusted peer", textContentForNearby(request))
  }

  @Test
  fun trustedMessage_envelope_isRejectedWithClearError_notClassCast() {
    val envelope = TrustedMessage(
      payload = byteArrayOf(1, 2, 3),
      timestamp = 0L,
      nonce = ByteArray(16) { 0 },
      signature = ByteArray(64) { 0 },
      senderId = "self",
      id = Random.nextInt(),
    )
    val request = SimpleSendMessageRequest(envelope)

    val error = assertFailsWith<IllegalArgumentException> {
      textContentForNearby(request)
    }
    assertTrue(
      error.message.orEmpty().contains("TextMessage"),
      "error should name the expected type: ${error.message}",
    )
    assertTrue(
      error.message.orEmpty().contains("TrustedMessage"),
      "error should name the actual type so the regression is obvious: ${error.message}",
    )
  }
}
