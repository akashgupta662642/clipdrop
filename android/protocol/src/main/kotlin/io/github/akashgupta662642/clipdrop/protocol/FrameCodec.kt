package io.github.akashgupta662642.clipdrop.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads and writes `[u32 length][u8 type][payload]` frames (PROTOCOL.md §7). */
public object FrameCodec {
    /**
     * Reads one frame. Blocking I/O runs on [Dispatchers.IO]; closing the underlying socket is
     * what unblocks a read that is in progress.
     *
     * @throws EOFException if the stream ends cleanly before a new frame starts.
     * @throws TruncatedFrameException if the stream ends inside a frame.
     * @throws ProtocolException with [ErrorCode.FRAME_TOO_LARGE] for a length of 0 or above the
     *   limit, detected before any payload buffer is allocated.
     */
    public suspend fun readFrame(input: InputStream): Frame =
        runInterruptible(Dispatchers.IO) { readFrameBlocking(input) }

    /** Blocking variant of [readFrame], for callers that already run on an I/O thread. */
    public fun readFrameBlocking(input: InputStream): Frame {
        val header = ByteArray(ProtocolConstants.LENGTH_PREFIX_SIZE)
        val headerRead = readUpTo(input, header)
        if (headerRead == 0) throw EOFException("Stream ended between frames")
        if (headerRead < header.size) throw TruncatedFrameException("Stream ended inside a frame length")

        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        if (length < 1 || length > ProtocolConstants.MAX_FRAME_LENGTH) {
            throw ProtocolException(ErrorCode.FRAME_TOO_LARGE, "Frame length $length is outside 1..${ProtocolConstants.MAX_FRAME_LENGTH}")
        }

        val body = ByteArray(length.toInt())
        if (readUpTo(input, body) < body.size) throw TruncatedFrameException("Stream ended inside a frame body")
        return Frame(body[0].toInt() and 0xFF, body.copyOfRange(1, body.size))
    }

    /** Encodes [frame] as wire bytes, length prefix included. */
    public fun encode(frame: Frame): ByteArray {
        val buffer = ByteBuffer.allocate(ProtocolConstants.LENGTH_PREFIX_SIZE + frame.length).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(frame.length)
        buffer.put(frame.typeCode.toByte())
        buffer.put(frame.payload)
        return buffer.array()
    }

    /** Fills [buffer] unless the stream ends first; returns the number of bytes read. */
    private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
