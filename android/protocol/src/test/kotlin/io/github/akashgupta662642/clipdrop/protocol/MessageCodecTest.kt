package io.github.akashgupta662642.clipdrop.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageCodecTest {
    private val transferId = "a3bb189e-8bf9-4888-9912-ace4e6543002"
    private val clipId = "3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162"

    @Test
    fun `every known message round-trips`() {
        val deviceId = DeviceIds.fromFingerprint(ByteArray(32) { it.toByte() })
        val messages: List<Message> = listOf(
            Hello(1, deviceId, "Pixel", "android", "0.1.0"),
            HelloAck(1, deviceId, "PC", "windows", "0.1.0", paired = false),
            PairRequest("AAECAwQFBgcICQoLDA0ODw"),
            PairResult(ok = false, reason = "invalid_token"),
            Unpair,
            Ping(Validation.MAX_NONCE),
            Pong(0),
            ClipText(clipId, "hi", 1),
            TransferOffer(transferId, TransferOffer.PURPOSE_FILE, "a.txt", "", 3, "text/plain"),
            TransferAccept(transferId, 0),
            TransferReject(transferId, "declined"),
            TransferChunk(transferId, 9, byteArrayOf(1, 2, 3)),
            TransferEnd(transferId, "0".repeat(64)),
            TransferResult(transferId, ok = true),
            TransferCancel(transferId),
            ErrorMessage("internal", "boom"),
            UnknownMessage(0x55, byteArrayOf(9)),
        )
        for (message in messages) {
            assertEquals(message, MessageCodec.decode(MessageCodec.encode(message)), "round trip of $message")
        }
    }

    @Test
    fun `encoding rejects messages that break the decode rules`() {
        val invalid: List<Message> = listOf(
            ClipText(clipId.uppercase(), "x", 1),
            ClipText(clipId, "", 1),
            ClipText(clipId, "lone \uD800", 1),
            Ping(-1),
            Ping(Validation.MAX_NONCE + 1),
            PairResult(ok = true, reason = "expired_token"),
            PairResult(ok = false),
            TransferAccept(transferId, ProtocolConstants.MAX_JSON_INTEGER + 1),
            TransferEnd(transferId, "A".repeat(64)),
            Hello(0, "x".repeat(43), "n", "android", "1"),
            Hello(1, "too-short", "n", "android", "1"),
            Hello(1, "x".repeat(43), "   ", "android", "1"),
        )
        for (message in invalid) {
            assertFailsWith<IllegalArgumentException>("should reject $message") { MessageCodec.encode(message) }
        }
    }

    @Test
    fun `fitsInFrame finds the largest clip that can be sent`() {
        val overhead = MessageCodec.encode(ClipText(clipId, "x", 1)).payload.size - 1
        val maxText = ProtocolConstants.MAX_FRAME_LENGTH - 1 - overhead
        assertTrue(MessageCodec.fitsInFrame(ClipText(clipId, "x".repeat(maxText), 1)))
        assertFalse(MessageCodec.fitsInFrame(ClipText(clipId, "x".repeat(maxText + 1), 1)))
        // Characters that need escaping count at their escaped size.
        assertFalse(MessageCodec.fitsInFrame(ClipText(clipId, "\n".repeat(maxText / 2 + 1), 1)))
    }

    @Test
    fun `toString never reveals clipboard text or pairing tokens`() {
        val clip = ClipText(clipId, "my secret password", 1).toString()
        val pair = PairRequest("AAECAwQFBgcICQoLDA0ODw").toString()
        assertFalse("secret" in clip)
        assertFalse("AAECAwQF" in pair)
    }

    @Test
    fun `unknown types decode to UnknownMessage instead of failing`() {
        val decoded = MessageCodec.decode(Frame(0x6A, "anything".encodeToByteArray()))
        assertEquals(UnknownMessage(0x6A, "anything".encodeToByteArray()), decoded)
    }
}
