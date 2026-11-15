package io.github.akashgupta662642.clipdrop.protocol

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameCodecTest {
    @Test
    fun `reads consecutive frames, then reports a clean end of stream`() {
        val first = Frame(MessageType.PING.code, """{"nonce":1}""".encodeToByteArray())
        val second = Frame(0x42, byteArrayOf(1, 2, 3))
        val input = ByteArrayInputStream(FrameCodec.encode(first) + FrameCodec.encode(second))

        assertEquals(first, FrameCodec.readFrameBlocking(input))
        assertEquals(second, FrameCodec.readFrameBlocking(input))
        val end = assertFailsWith<EOFException> { FrameCodec.readFrameBlocking(input) }
        assertTrue(end !is TruncatedFrameException, "a clean end between frames is not truncation")
    }

    @Test
    fun `handles a stream that returns one byte per read`() {
        val frame = Frame(MessageType.CLIP_TEXT.code, ByteArray(1000) { it.toByte() })
        val trickle = object : InputStream() {
            val bytes = ByteArrayInputStream(FrameCodec.encode(frame))
            override fun read(): Int = bytes.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else bytes.read(b, off, 1)
        }
        assertEquals(frame, FrameCodec.readFrameBlocking(trickle))
    }

    @Test
    fun `rejects an oversized length before reading or allocating the body`() {
        var bytesServed = 0
        val header = byteArrayOf(0x7F, -1, -1, -1)
        val hostile = object : InputStream() {
            override fun read(): Int {
                check(bytesServed < header.size) { "codec read past the length field" }
                return header[bytesServed++].toInt() and 0xFF
            }
        }
        val error = assertFailsWith<ProtocolException> { FrameCodec.readFrameBlocking(hostile) }
        assertEquals(ErrorCode.FRAME_TOO_LARGE, error.code)
        assertEquals(4, bytesServed)
    }

    @Test
    fun `round-trips a frame at the maximum length`() {
        val frame = Frame(MessageType.TRANSFER_CHUNK.code, ByteArray(ProtocolConstants.MAX_FRAME_LENGTH - 1) { 7 })
        val bytes = FrameCodec.encode(frame)
        assertEquals(ProtocolConstants.LENGTH_PREFIX_SIZE + ProtocolConstants.MAX_FRAME_LENGTH, bytes.size)
        assertEquals(frame, FrameCodec.readFrameBlocking(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `refuses to build a frame above the limit`() {
        assertFailsWith<IllegalArgumentException> { Frame(1, ByteArray(ProtocolConstants.MAX_FRAME_LENGTH)) }
        assertFailsWith<IllegalArgumentException> { Frame(256, ByteArray(0)) }
    }

    @Test
    fun `suspending read works off the caller's thread`() = runBlocking {
        val frame = Frame(MessageType.UNPAIR.code, "{}".encodeToByteArray())
        assertEquals(frame, FrameCodec.readFrame(ByteArrayInputStream(FrameCodec.encode(frame))))
    }
}
