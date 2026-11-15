package io.github.akashgupta662642.clipdrop.engine.session

import io.github.akashgupta662642.clipdrop.protocol.Frame
import io.github.akashgupta662642.clipdrop.protocol.FrameCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket

/** A bidirectional stream of frames. Sessions are written against this, so tests can use in-memory pipes. */
public interface FrameTransport {
    /**
     * Reads the next frame. Cancelling the caller closes the transport, which is the only reliable
     * way to unblock a socket read.
     *
     * @throws EOFException when the peer closed the connection.
     */
    public suspend fun readFrame(): Frame

    public suspend fun writeFrame(frame: Frame)

    /** Closes the connection. Idempotent; pending reads and writes fail with [IOException]. */
    public fun close()
}

/** [FrameTransport] over a connected (TLS) socket. */
public class SocketFrameTransport(private val socket: Socket) : FrameTransport {
    private val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
    private val output = socket.getOutputStream()

    override suspend fun readFrame(): Frame = coroutineScope {
        val reading = async(Dispatchers.IO) { FrameCodec.readFrameBlocking(input) }
        try {
            reading.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }
    }

    override suspend fun writeFrame(frame: Frame) {
        val bytes = FrameCodec.encode(frame)
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    /** Sets the socket read timeout; used during the handshake (§9.3), cleared once the session is live. */
    public fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
