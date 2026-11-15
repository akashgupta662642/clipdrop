package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.engine.session.CloseReason
import io.github.akashgupta662642.clipdrop.engine.session.PeerSession
import io.github.akashgupta662642.clipdrop.protocol.ClipText
import io.github.akashgupta662642.clipdrop.protocol.ErrorCode
import io.github.akashgupta662642.clipdrop.protocol.ErrorMessage
import io.github.akashgupta662642.clipdrop.protocol.Frame
import io.github.akashgupta662642.clipdrop.protocol.Hello
import io.github.akashgupta662642.clipdrop.protocol.MessageType
import io.github.akashgupta662642.clipdrop.protocol.Ping
import io.github.akashgupta662642.clipdrop.protocol.Pong
import io.github.akashgupta662642.clipdrop.protocol.TransferCancel
import io.github.akashgupta662642.clipdrop.protocol.TransferChunk
import io.github.akashgupta662642.clipdrop.protocol.Unpair
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PeerSessionTest {
    private val transferId = "a3bb189e-8bf9-4888-9912-ace4e6543002"
    private val clipId = "3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162"

    /** A started session on virtual time, its own end of the pipe, and the peer's end. */
    private class Harness(val session: PeerSession, val local: PipeTransport, val peer: PipeTransport)

    private fun TestScope.startSession(): Harness {
        val (local, peer) = PipeTransport.pair()
        val session = PeerSession(local, backgroundScope, timeSource = testTimeSource)
        session.start()
        return Harness(session, local, peer)
    }

    @Test
    fun `answers PING with PONG carrying the same nonce`() = runTest {
        val h = startSession()
        h.peer.send(Ping(77))
        assertEquals(Pong(77), h.peer.receive())
    }

    @Test
    fun `ping returns once the matching PONG arrives`() = runTest {
        val h = startSession()
        val rtt = async { h.session.ping() }
        val ping = assertIs<Ping>(h.peer.receive())
        advanceTimeBy(250.milliseconds)
        h.peer.send(Pong(ping.nonce))
        assertEquals(250, rtt.await().inWholeMilliseconds)
    }

    @Test
    fun `delivers application messages to incoming`() = runTest {
        val h = startSession()
        h.peer.send(ClipText(clipId, "hello", 1))
        assertEquals(ClipText(clipId, "hello", 1), h.session.incoming.first())
    }

    @Test
    fun `sends PING after 15 s of silence and closes after 45 s`() = runTest {
        val h = startSession()
        advanceTimeBy(14.seconds)
        runCurrent()
        assertFalse(h.peer.hasPendingFrame(), "no PING before 15 s")

        advanceTimeBy(2.seconds)
        assertIs<Ping>(h.peer.receive())

        advanceTimeBy(15.seconds)
        assertIs<Ping>(h.peer.receive(), "another PING after a further 15 s of silence")

        advanceTimeBy(15.seconds)
        assertEquals(CloseReason.HeartbeatTimeout, h.session.closeReason.await())
    }

    @Test
    fun `any received frame resets the heartbeat`() = runTest {
        val h = startSession()
        repeat(5) {
            advanceTimeBy(14.seconds)
            h.peer.send(ClipText(clipId, "keep-alive $it", 1))
            runCurrent()
        }
        assertTrue(h.session.isOpen)
        assertFalse(h.peer.hasPendingFrame(), "a busy peer never needs a PING")
    }

    @Test
    fun `control frames overtake queued data frames`() = runTest {
        val h = startSession()
        fun chunk(offset: Long) = TransferChunk(transferId, offset, byteArrayOf(1))

        h.local.writeGate.lock() // Hold the writer while frames queue up.
        h.session.send(chunk(0))
        runCurrent() // The writer has taken chunk 0 and is blocked writing it.
        h.session.send(chunk(1))
        h.session.send(chunk(2))
        h.session.send(TransferCancel(transferId))
        h.local.writeGate.unlock()

        val order = List(4) { h.peer.receive() }.map { if (it is TransferChunk) "chunk${it.offset}" else it.javaClass.simpleName }
        assertEquals(listOf("chunk0", "TransferCancel", "chunk1", "chunk2"), order)
    }

    @Test
    fun `data queue applies backpressure after eight frames`() = runTest {
        val h = startSession()
        h.local.writeGate.lock()
        h.session.send(TransferChunk(transferId, 0, byteArrayOf(1))) // taken by the blocked writer
        runCurrent()
        repeat(8) { h.session.send(TransferChunk(transferId, it + 1L, byteArrayOf(1))) }
        val ninth = launch { h.session.send(TransferChunk(transferId, 9, byteArrayOf(1))) }
        runCurrent()
        assertTrue(ninth.isActive, "the ninth queued chunk must wait")

        h.local.writeGate.unlock()
        runCurrent()
        assertFalse(ninth.isActive)
    }

    @Test
    fun `unknown frame types get an ERROR reply and the session carries on`() = runTest {
        val h = startSession()
        h.peer.writeFrame(Frame(0x42, byteArrayOf(1, 2)))
        val error = assertIs<ErrorMessage>(h.peer.receive())
        assertEquals(ErrorCode.UNKNOWN_TYPE.wireName, error.code)

        h.peer.send(Ping(1))
        assertEquals(Pong(1), h.peer.receive())
        assertTrue(h.session.isOpen)
    }

    @Test
    fun `a malformed frame gets ERROR protocol_error, then the session closes`() = runTest {
        val h = startSession()
        h.peer.writeFrame(Frame(MessageType.PING.code, """{"nonce":"1"}""".encodeToByteArray()))
        val error = assertIs<ErrorMessage>(h.peer.receive())
        assertEquals(ErrorCode.PROTOCOL_ERROR.wireName, error.code)
        assertIs<CloseReason.ProtocolViolation>(h.session.closeReason.await())
        assertTrue(h.local.isClosed)
    }

    @Test
    fun `handshake messages are not allowed in a live session`() = runTest {
        val h = startSession()
        h.peer.send(Hello(1, "A".repeat(43), "x", "android", "1"))
        assertIs<ErrorMessage>(h.peer.receive())
        assertIs<CloseReason.ProtocolViolation>(h.session.closeReason.await())
    }

    @Test
    fun `peer closing the connection ends the session`() = runTest {
        val h = startSession()
        h.peer.close()
        assertEquals(CloseReason.RemoteClosed, h.session.closeReason.await())
    }

    @Test
    fun `peer ERROR other than unknown_type closes the session`() = runTest {
        val h = startSession()
        h.peer.send(ErrorMessage("internal", "boom"))
        assertEquals(CloseReason.PeerError(ErrorCode.INTERNAL, "boom"), h.session.closeReason.await())
    }

    @Test
    fun `receiving UNPAIR ends the session`() = runTest {
        val h = startSession()
        h.peer.send(Unpair)
        assertEquals(CloseReason.Unpaired, h.session.closeReason.await())
    }

    @Test
    fun `unpair sends UNPAIR before closing`() = runTest {
        val h = startSession()
        h.session.unpair()
        assertEquals(Unpair, h.peer.receive())
        assertEquals(CloseReason.Local, h.session.closeReason.await())
    }
}
