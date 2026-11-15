package io.github.akashgupta662642.clipdrop.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs every vector in docs/test-vectors (PROTOCOL.md §16). The .NET suite runs the same files. */
class GoldenVectorTest {
    @TestFactory
    fun `positive frames decode and re-encode byte for byte`(): List<DynamicTest> =
        TestVectors.vectors("frames.json").map { vector ->
            dynamicTest(vector.string("name")) {
                val input = ByteArrayInputStream(vector.string("hex").hexToBytes())
                val frame = FrameCodec.readFrameBlocking(input)
                assertEquals(0, input.available(), "the frame must consume every byte")
                assertEquals(vector.int("typeCode"), frame.typeCode)

                val decoded = MessageCodec.decode(frame)
                assertEquals(expectedMessage(vector), decoded)

                val reencoded = FrameCodec.encode(MessageCodec.encode(decoded)).toHex()
                assertEquals(vector.stringOrNull("reencodedHex") ?: vector.string("hex"), reencoded)
            }
        }

    @TestFactory
    fun `negative frames fail with the expected error`(): List<DynamicTest> =
        TestVectors.vectors("invalid-frames.json").map { vector ->
            dynamicTest(vector.string("name")) {
                val input = ByteArrayInputStream(vector.string("hex").hexToBytes())
                when (val expected = vector.string("error")) {
                    "frame_too_large" -> {
                        val error = assertFailsWith<ProtocolException> { FrameCodec.readFrameBlocking(input) }
                        assertEquals(ErrorCode.FRAME_TOO_LARGE, error.code)
                    }
                    "truncated" -> assertFailsWith<TruncatedFrameException> { FrameCodec.readFrameBlocking(input) }
                    "protocol_error" -> {
                        val frame = FrameCodec.readFrameBlocking(input)
                        assertEquals(0, input.available(), "the frame itself must be well formed")
                        val error = assertFailsWith<ProtocolException> { MessageCodec.decode(frame) }
                        assertEquals(ErrorCode.PROTOCOL_ERROR, error.code)
                    }
                    else -> error("Unknown expected error $expected")
                }
            }
        }

    @TestFactory
    fun `pairing URIs parse as specified`(): List<DynamicTest> =
        TestVectors.vectors("pairing-uri.json").map { vector ->
            dynamicTest(vector.string("name")) {
                val result = PairingUri.parse(vector.string("uri"))
                when (vector.stringOrNull("error")) {
                    null -> {
                        val expected = vector.getValue("expected").jsonObject
                        val uri = assertIs<PairingUri.ParseResult.Valid>(result).uri
                        assertEquals(expected.int("version"), uri.version)
                        assertEquals(expected.getValue("hosts").jsonArray.map { it.jsonPrimitive.content }, uri.hosts)
                        assertEquals(expected.int("port"), uri.port)
                        assertEquals(expected.string("fingerprint"), uri.fingerprint)
                        assertEquals(expected.string("token"), uri.token)
                        assertEquals(expected.string("name"), uri.name)
                        // What we build, we must parse back to the same value.
                        assertEquals(result, PairingUri.parse(uri.toUriString()))
                    }
                    "invalid" -> assertIs<PairingUri.ParseResult.Invalid>(result)
                    "unsupported_version" -> assertEquals(PairingUri.ParseResult.UnsupportedVersion, result)
                    else -> error("Unknown expected error")
                }
            }
        }

    @Test
    fun `device IDs derive from the certificate fingerprint`() {
        val identity = TestVectors.file("identity.json")
        val der = identity.string("certificateDerHex").hexToBytes()
        assertEquals(identity.string("fingerprintHex"), DeviceIds.fingerprint(der).toHex())
        assertEquals(identity.string("deviceId"), DeviceIds.deviceId(der))

        val all = identity.getValue("extraDeviceIds").jsonArray.map { it.jsonObject } +
            JsonObject(mapOf("fingerprintHex" to identity.getValue("fingerprintHex"), "deviceId" to identity.getValue("deviceId")))
        for (entry in all) {
            val fingerprint = entry.string("fingerprintHex").hexToBytes()
            assertEquals(entry.string("deviceId"), DeviceIds.fromFingerprint(fingerprint))
            assertTrue(fingerprint.contentEquals(DeviceIds.toFingerprint(entry.string("deviceId"))))
        }
    }

    private fun expectedMessage(vector: JsonObject): Message {
        val message = vector.getValue("message").jsonObject
        return when (vector.string("type")) {
            "UNKNOWN" -> UnknownMessage(vector.int("typeCode"), message.string("payloadHex").hexToBytes())
            "TRANSFER_CHUNK" -> TransferChunk(
                message.string("transferId"),
                message.getValue("offset").jsonPrimitive.long,
                message.string("dataHex").hexToBytes(),
            )
            else -> MessageCodec.decode(Frame(vector.int("typeCode"), message.toString().encodeToByteArray()))
        }
    }
}
