package com.carlom.klardrop.common.communication.message

import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class AckMessageTest {

    private val testSerializer = MessageSerializer(ProtoBuf { encodeDefaults = true }, Coroutines.Default)

    @Test
    fun testAckMessageSerializationDeserialization() = runBlocking {
        val originalMessage = AckMessage(ackedMessageId = "test-uuid-123")

        val serialized = testSerializer.serialize(originalMessage)
        val deserialized = testSerializer.deserialize(serialized)

        assertIs<AckMessage>(deserialized, "Deserialized message should be AckMessage")
        assertEquals(originalMessage.ackedMessageId, deserialized.ackedMessageId, "AckedMessageId should match")
        assertEquals(MessageType.ACK, deserialized.type, "Message type should be ACK")
        assertFalse(deserialized.hasPayload, "hasPayload should be false")
        assertNull(deserialized.messageId, "AckMessage's own messageId should be null")
    }
}
