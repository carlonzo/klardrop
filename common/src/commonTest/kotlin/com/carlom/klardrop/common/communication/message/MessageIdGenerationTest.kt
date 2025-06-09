package com.carlom.klardrop.common.communication.message

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class MessageIdGenerationTest {

    @Test
    fun textMessageGeneratesUniqueIds() {
        val message1 = TextMessage(text = "Hello 1")
        val message2 = TextMessage(text = "Hello 2")

        assertNotNull(message1.messageId, "Message1 ID should not be null")
        assertTrue(message1.messageId!!.isNotEmpty(), "Message1 ID should not be empty")

        assertNotNull(message2.messageId, "Message2 ID should not be null")
        assertTrue(message2.messageId!!.isNotEmpty(), "Message2 ID should not be empty")

        assertNotEquals(message1.messageId, message2.messageId, "Message IDs should be unique")
    }

    @Test
    fun fileMessageGeneratesUniqueIds() {
        val message1 = FileMessage(fileName = "file1.txt", fileSize = 100, mimeType = "text/plain")
        val message2 = FileMessage(fileName = "file2.txt", fileSize = 200, mimeType = "text/plain")

        assertNotNull(message1.messageId, "Message1 ID should not be null")
        assertTrue(message1.messageId.isNotEmpty(), "Message1 ID should not be empty")

        assertNotNull(message2.messageId, "Message2 ID should not be null")
        assertTrue(message2.messageId.isNotEmpty(), "Message2 ID should not be empty")

        assertNotEquals(message1.messageId, message2.messageId, "Message IDs should be unique")
    }
}
