package io.github.akashgupta662642.clipdrop.engine.handshake

import io.github.akashgupta662642.clipdrop.engine.session.FrameTransport
import io.github.akashgupta662642.clipdrop.protocol.ErrorCode
import io.github.akashgupta662642.clipdrop.protocol.ErrorMessage
import io.github.akashgupta662642.clipdrop.protocol.Hello
import io.github.akashgupta662642.clipdrop.protocol.HelloAck
import io.github.akashgupta662642.clipdrop.protocol.Message
import io.github.akashgupta662642.clipdrop.protocol.MessageCodec
import io.github.akashgupta662642.clipdrop.protocol.PairRequest
import io.github.akashgupta662642.clipdrop.protocol.PairResult
import io.github.akashgupta662642.clipdrop.protocol.ProtocolConstants
import io.github.akashgupta662642.clipdrop.protocol.ProtocolException
import io.github.akashgupta662642.clipdrop.protocol.UnknownMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.time.Duration

/** What this device says about itself in HELLO. */
public data class LocalDevice(
    val deviceId: String,
    val name: String,
    val appVersion: String,
    val platform: String = "android",
    val minProtocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    val maxProtocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
)

/** What the host said about itself in HELLO_ACK. */
public data class HostInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val protocolVersion: Int,
)

public sealed interface HandshakeOutcome {
    /** The session can go live. [newlyPaired] is true if this handshake just completed pairing. */
    public data class Live(val host: HostInfo, val newlyPaired: Boolean) : HandshakeOutcome

    /** The host refused the pairing token (§10.1); [reason] is the wire reason. */
    public data class PairingRejected(val reason: String) : HandshakeOutcome

    /**
     * The host no longer knows this phone (§9.2): it was unpaired on the PC while we were away.
     * The connection only got through because the PC happened to be in pairing mode.
     */
    public data object ForgottenByHost : HandshakeOutcome
}

/** The peer sent ERROR during the handshake. */
public class PeerErrorException(public val code: ErrorCode, message: String) : IOException(message)

/** The handshake took longer than allowed (§9.3). */
public class HandshakeTimeoutException(message: String) : IOException(message)

/**
 * Client side of the handshake and pairing steps of PROTOCOL.md §9.1 and §10.2. Runs directly on
 * the transport, before a [io.github.akashgupta662642.clipdrop.engine.session.PeerSession] exists.
 * On any failure the transport is closed.
 */
public object ClientHandshake {
    public suspend fun perform(
        transport: FrameTransport,
        local: LocalDevice,
        expectedHostId: String,
        pairingToken: String?,
        timeout: Duration = ProtocolConstants.HANDSHAKE_TIMEOUT,
    ): HandshakeOutcome {
        try {
            transport.writeFrame(
                MessageCodec.encode(Hello(local.maxProtocolVersion, local.deviceId, local.name, local.platform, local.appVersion)),
            )
            val ack = receive<HelloAck>(transport, timeout)

            if (ack.protocolVersion !in local.minProtocolVersion..local.maxProtocolVersion) {
                fail(transport, ErrorCode.UNSUPPORTED_VERSION, "No common protocol version (host chose ${ack.protocolVersion})")
            }
            if (ack.deviceId != expectedHostId) {
                fail(transport, ErrorCode.PROTOCOL_ERROR, "HELLO_ACK deviceId doesn't match the pinned certificate")
            }
            val host = HostInfo(ack.deviceId, ack.deviceName.trim(), ack.platform, ack.appVersion, ack.protocolVersion)

            if (ack.paired) return HandshakeOutcome.Live(host, newlyPaired = false)
            if (pairingToken == null) {
                transport.close()
                return HandshakeOutcome.ForgottenByHost
            }

            transport.writeFrame(MessageCodec.encode(PairRequest(pairingToken)))
            val result = receive<PairResult>(transport, timeout)
            if (result.ok) return HandshakeOutcome.Live(host, newlyPaired = true)
            transport.close()
            return HandshakeOutcome.PairingRejected(result.reason.orEmpty())
        } catch (e: Throwable) {
            transport.close()
            throw e
        }
    }

    /** Reads until a message of type [T] arrives, skipping unknown types (§15.2). */
    private suspend inline fun <reified T : Message> receive(transport: FrameTransport, timeout: Duration): T {
        try {
            return withTimeout(timeout) {
                var result: T? = null
                while (result == null) {
                    val message = try {
                        MessageCodec.decode(transport.readFrame())
                    } catch (e: ProtocolException) {
                        // The host sent something malformed: tell it, then give up.
                        fail(transport, e.code, e.message.orEmpty())
                    }
                    when (message) {
                        is T -> result = message
                        is ErrorMessage -> throw PeerErrorException(ErrorCode.fromWireName(message.code), message.message)
                        is UnknownMessage -> transport.writeFrame(
                            MessageCodec.encode(ErrorMessage(ErrorCode.UNKNOWN_TYPE.wireName, "Unknown frame type 0x%02x".format(message.typeCode))),
                        )
                        else -> fail(transport, ErrorCode.NOT_PAIRED, "${message.javaClass.simpleName} arrived during the handshake")
                    }
                }
                result
            }
        } catch (_: TimeoutCancellationException) {
            throw HandshakeTimeoutException("No ${T::class.simpleName} within $timeout")
        }
    }

    private suspend fun fail(transport: FrameTransport, code: ErrorCode, detail: String): Nothing {
        runCatching { transport.writeFrame(MessageCodec.encode(ErrorMessage(code.wireName, detail.take(256)))) }
        transport.close()
        throw ProtocolException(code, detail)
    }
}
