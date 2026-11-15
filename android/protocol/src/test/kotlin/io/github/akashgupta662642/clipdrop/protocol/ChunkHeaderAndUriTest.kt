package io.github.akashgupta662642.clipdrop.protocol

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ChunkHeaderAndUriTest {
    @Test
    fun `chunk header puts the UUID in network byte order, then a big-endian offset`() {
        val payload = ChunkHeader.encodePayload("00112233-4455-6677-8899-aabbccddeeff", 0x0013040506070809L, byteArrayOf(0x7F))
        assertEquals("00112233445566778899aabbccddeeff" + "0013040506070809" + "7f", payload.toHex())
    }

    @Test
    fun `chunk data must be between 1 byte and 256 KiB`() {
        val id = "00112233-4455-6677-8899-aabbccddeeff"
        assertEquals(ChunkHeader.SIZE + ProtocolConstants.MAX_CHUNK_DATA, ChunkHeader.encodePayload(id, 0, ByteArray(ProtocolConstants.MAX_CHUNK_DATA)).size)
        assertFailsWith<IllegalArgumentException> { ChunkHeader.encodePayload(id, 0, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { ChunkHeader.encodePayload(id, 0, ByteArray(ProtocolConstants.MAX_CHUNK_DATA + 1)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Office PC", "Akash’s PC 💻", "a+b & c=d", "100% done", "  leading?", "日本語"])
    fun `pairing URIs round-trip any device name`(rawName: String) {
        val name = rawName.trim()
        val uri = PairingUri(listOf("192.168.1.20", "10.0.0.7"), 47821, "A".repeat(43), "B".repeat(22), name)
        val parsed = assertIs<PairingUri.ParseResult.Valid>(PairingUri.parse(uri.toUriString()))
        assertEquals(uri, parsed.uri)
    }

    @Test
    fun `pairing URI toString hides the token`() {
        val uri = PairingUri(listOf("10.0.0.1"), 1, "A".repeat(43), "SecretTokenValue123456", "PC")
        assertFalse("SecretTokenValue" in uri.toString())
    }
}
