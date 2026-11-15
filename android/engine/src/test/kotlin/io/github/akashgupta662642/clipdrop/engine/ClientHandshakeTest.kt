package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.engine.handshake.ClientHandshake
import io.github.akashgupta662642.clipdrop.engine.handshake.HandshakeOutcome
import io.github.akashgupta662642.clipdrop.engine.handshake.HandshakeTimeoutException
import io.github.akashgupta662642.clipdrop.engine.handshake.HostInfo
import io.github.akashgupta662642.clipdrop.engine.handshake.LocalDevice
import io.github.akashgupta662642.clipdrop.engine.handshake.PeerErrorException
import io.github.akashgupta662642.clipdrop.protocol.ErrorCode
import io.github.akashgupta662642.clipdrop.protocol.ErrorMessage
import io.github.akashgupta662642.clipdrop.protocol.Frame
import io.github.akashgupta662642.clipdrop.protocol.Hello
import io.github.akashgupta662642.clipdrop.protocol.HelloAck
import io.github.akashgupta662642.clipdrop.protocol.Message
import io.github.akashgupta662642.clipdrop.protocol.MessageCodec
import io.github.akashgupta662642.clipdrop.protocol.PairRequest
import io.github.akashgupta662642.clipdrop.protocol.PairResult
import io.github.akashgupta662642.clipdrop.protocol.Ping
import io.github.akashgupta662642.clipdrop.protocol.ProtocolException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientHandshakeTest {
    private val phoneId = "z1U7rcmOGpN1lOltb_aoROFV1Gt_gzcyEcocHv9UzmY"
    private val hostId = "A".repeat(43)
    private val token = "AAECAwQFBgcICQoLDA0ODw"
    private val local = LocalDevice(phoneId, "Pixel 9", "0.1.0")

    private fun ack(paired: Boolean, version: Int = 1, id: String = hostId) =
        HelloAck(version, id, " Office PC ", "windows", "0.2.0", paired)

    /** The outcome of one handshake, plus everything the scripted host received. */
    private class Run(val result: Result<HandshakeOutcome>, val hostReceived: List<Message>, val phoneClosed: Boolean)

    /** The PC's end of the pipe, as seen by a test script. Records everything it receives. */
    private class HostSide(private val pipe: PipeTransport) {
        val received = mutableListOf<Message>()

        suspend fun receive(): Message = pipe.receive().also { received += it }
        suspend fun send(message: Message) = pipe.send(message)
        suspend fun writeFrame(frame: Frame) = pipe.writeFrame(frame)

        /** Records frames the phone sent that the script didn't read, such as a final ERROR. */
        fun drain() = generateSequence { pipe.pollFrame() }.forEach { received += MessageCodec.decode(it) }
    }

    /**
     * Runs the phone's handshake against [script], which plays the PC. The handshake runs in the
     * test body, so an expected failure is just a failed [Result], not a failed test scope.
     */
    private suspend fun TestScope.handshakeScript(pairingToken: String?, script: suspend HostSide.() -> Unit): Run {
        val (phone, hostEnd) = PipeTransport.pair()
        val host = HostSide(hostEnd)
        backgroundScope.launch { host.script() }
        val result = runCatching { ClientHandshake.perform(phone, local, hostId, pairingToken) }
        host.drain()
        return Run(result, host.received, phone.isClosed)
    }

    @Test
    fun `a paired phone goes straight to live`() = runTest {
        val run = handshakeScript(null) {
            receive()
            send(ack(paired = true))
        }
        assertEquals(
            HandshakeOutcome.Live(HostInfo(hostId, "Office PC", "windows", "0.2.0", 1), newlyPaired = false),
            run.result.getOrThrow(),
        )
        assertEquals(Hello(1, phoneId, "Pixel 9", "android", "0.1.0"), run.hostReceived.first())
    }

    @Test
    fun `an unpaired phone with a token sends PAIR_REQUEST and goes live on success`() = runTest {
        val run = handshakeScript(token) {
            receive()
            send(ack(paired = false))
            receive()
            send(PairResult(ok = true))
        }
        assertTrue(assertIs<HandshakeOutcome.Live>(run.result.getOrThrow()).newlyPaired)
        assertEquals(PairRequest(token), run.hostReceived[1])
    }

    @Test
    fun `a rejected token reports the host's reason and closes`() = runTest {
        val run = handshakeScript(token) {
            receive()
            send(ack(paired = false))
            receive()
            send(PairResult(ok = false, reason = "expired_token"))
        }
        assertEquals(HandshakeOutcome.PairingRejected("expired_token"), run.result.getOrThrow())
        assertTrue(run.phoneClosed)
    }

    @Test
    fun `a host that forgot this phone is reported, never re-paired without a token`() = runTest {
        val run = handshakeScript(null) {
            receive()
            send(ack(paired = false))
        }
        assertEquals(HandshakeOutcome.ForgottenByHost, run.result.getOrThrow())
        assertTrue(run.phoneClosed)
        assertEquals(1, run.hostReceived.size, "nothing but HELLO: no PAIR_REQUEST without a token")
    }

    @Test
    fun `a HELLO_ACK claiming a different device ID is a protocol error`() = runTest {
        val run = handshakeScript(null) {
            receive()
            send(ack(paired = true, id = "B".repeat(43)))
        }
        assertEquals(ErrorCode.PROTOCOL_ERROR, assertIs<ProtocolException>(run.result.exceptionOrNull()).code)
        assertEquals(ErrorCode.PROTOCOL_ERROR.wireName, assertIs<ErrorMessage>(run.hostReceived.last()).code)
    }

    @Test
    fun `a version outside our range is refused with unsupported_version`() = runTest {
        val run = handshakeScript(null) {
            receive()
            send(ack(paired = true, version = 2))
        }
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, assertIs<ProtocolException>(run.result.exceptionOrNull()).code)
        assertEquals(ErrorCode.UNSUPPORTED_VERSION.wireName, assertIs<ErrorMessage>(run.hostReceived.last()).code)
    }

    @Test
    fun `an ERROR from the host fails the handshake`() = runTest {
        val run = handshakeScript(null) {
            receive()
            send(ErrorMessage("unsupported_version", "too old"))
        }
        assertEquals(ErrorCode.UNSUPPORTED_VERSION, assertIs<PeerErrorException>(run.result.exceptionOrNull()).code)
    }

    @Test
    fun `frames other than the expected reply are refused with not_paired`() = runTest {
        val run = handshakeScript(null) {
            receive()
            send(Ping(1))
        }
        assertEquals(ErrorCode.NOT_PAIRED, assertIs<ProtocolException>(run.result.exceptionOrNull()).code)
    }

    @Test
    fun `unknown frame types are answered and skipped`() = runTest {
        val run = handshakeScript(null) {
            receive()
            writeFrame(Frame(0x42, ByteArray(0)))
            assertEquals(ErrorCode.UNKNOWN_TYPE.wireName, assertIs<ErrorMessage>(receive()).code)
            send(ack(paired = true))
        }
        assertIs<HandshakeOutcome.Live>(run.result.getOrThrow())
    }

    @Test
    fun `a silent host times out after 10 seconds`() = runTest {
        val run = handshakeScript(null) { receive() }
        assertIs<HandshakeTimeoutException>(run.result.exceptionOrNull())
        assertEquals(10_000, testScheduler.currentTime)
        assertTrue(run.phoneClosed)
    }
}
