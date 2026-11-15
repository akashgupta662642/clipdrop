package io.github.akashgupta662642.clipdrop.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageTypeTest {
    @ParameterizedTest
    @EnumSource(MessageType::class)
    fun `every type round-trips through its code`(type: MessageType) {
        assertEquals(type, MessageType.fromCode(type.code))
    }

    @ParameterizedTest
    @ValueSource(ints = [0x00, 0x06, 0x12, 0x21, 0x37, 0x7E, 0x80, 0xFF])
    fun `unknown codes are not mapped`(code: Int) {
        assertNull(MessageType.fromCode(code))
    }

    @Test
    fun `codes match the message table in the spec`() {
        val expected = mapOf(
            "HELLO" to 0x01, "HELLO_ACK" to 0x02, "PAIR_REQUEST" to 0x03, "PAIR_RESULT" to 0x04,
            "UNPAIR" to 0x05, "PING" to 0x10, "PONG" to 0x11, "CLIP_TEXT" to 0x20,
            "TRANSFER_OFFER" to 0x30, "TRANSFER_ACCEPT" to 0x31, "TRANSFER_REJECT" to 0x32,
            "TRANSFER_CHUNK" to 0x33, "TRANSFER_END" to 0x34, "TRANSFER_RESULT" to 0x35,
            "TRANSFER_CANCEL" to 0x36, "ERROR" to 0x7F,
        )
        assertEquals(expected, MessageType.entries.associate { it.name to it.code })
    }

    @Test
    fun `only TRANSFER_CHUNK is binary`() {
        assertEquals(
            listOf(MessageType.TRANSFER_CHUNK),
            MessageType.entries.filter { it.payloadKind == PayloadKind.BINARY },
        )
    }

    @Test
    fun `only pairing frames and ERROR are allowed before pairing`() {
        assertEquals(
            setOf(
                MessageType.HELLO, MessageType.HELLO_ACK, MessageType.PAIR_REQUEST,
                MessageType.PAIR_RESULT, MessageType.ERROR,
            ),
            MessageType.entries.filter { it.allowedBeforePairing }.toSet(),
        )
    }

    @Test
    fun `the largest chunk frame fits within the frame limit`() {
        val largestChunkFrame = 1 + ProtocolConstants.CHUNK_HEADER_SIZE + ProtocolConstants.MAX_CHUNK_DATA
        assertTrue(largestChunkFrame <= ProtocolConstants.MAX_FRAME_LENGTH)
    }
}
