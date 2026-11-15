package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.engine.handshake.ClientHandshake
import io.github.akashgupta662642.clipdrop.engine.handshake.HandshakeOutcome
import io.github.akashgupta662642.clipdrop.engine.handshake.HandshakeTimeoutException
import io.github.akashgupta662642.clipdrop.engine.handshake.HostInfo
import io.github.akashgupta662642.clipdrop.engine.handshake.LocalDevice
import io.github.akashgupta662642.clipdrop.engine.handshake.PeerErrorException
import io.github.akashgupta662642.clipdrop.engine.session.PeerSession
import io.github.akashgupta662642.clipdrop.engine.session.SocketFrameTransport
import io.github.akashgupta662642.clipdrop.engine.tls.PinMismatchException
import io.github.akashgupta662642.clipdrop.engine.tls.TlsConnector
import io.github.akashgupta662642.clipdrop.protocol.DeviceIds
import io.github.akashgupta662642.clipdrop.protocol.ErrorCode
import io.github.akashgupta662642.clipdrop.protocol.PairingUri
import io.github.akashgupta662642.clipdrop.protocol.ProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import javax.net.ssl.SSLException

/** Why connecting or pairing failed, in terms the UI can explain (PROTOCOL.md §10.3). */
public enum class ConnectFailure {
    /** No address answered in time: a different network, or a firewall dropping packets. */
    UNREACHABLE,

    /** Every address refused the connection: ClipDrop isn't listening there. */
    REFUSED,

    /** Something answered, but not the PC we pinned. */
    PIN_MISMATCH,

    /** TLS failed for another reason. */
    TLS_FAILED,
    PROTOCOL_ERROR,
    UNSUPPORTED_VERSION,
    TOKEN_EXPIRED,
    TOKEN_INVALID,
    PAIRING_CLOSED,
    TOO_MANY_ATTEMPTS,

    /** The PC was unpaired from this phone (§9.2). */
    FORGOTTEN_BY_HOST,
}

public sealed interface ConnectOutcome {
    public data class Connected(
        val host: HostInfo,
        val address: InetSocketAddress,
        val session: PeerSession,
        val newlyPaired: Boolean,
    ) : ConnectOutcome

    public data class Failed(val failure: ConnectFailure) : ConnectOutcome
}

/**
 * Connects to a host by trying its candidate addresses in order (§6, §5), completing TLS with the
 * host's pin and the handshake, and returns a started [PeerSession]. Also performs pairing when
 * given a token from a scanned QR code.
 */
public class HostConnector(
    private val tls: TlsConnector,
    private val local: LocalDevice,
) {
    /** Pairs using a scanned pairing URI (§10.2). */
    public suspend fun pair(uri: PairingUri, scope: CoroutineScope): ConnectOutcome =
        connect(uri.hosts.map { InetSocketAddress(it, uri.port) }, uri.fingerprint, uri.token, scope)

    /** Reconnects to an already-paired host. */
    public suspend fun connect(addresses: List<InetSocketAddress>, hostId: String, scope: CoroutineScope): ConnectOutcome =
        connect(addresses, hostId, pairingToken = null, scope)

    private suspend fun connect(
        addresses: List<InetSocketAddress>,
        hostId: String,
        pairingToken: String?,
        scope: CoroutineScope,
    ): ConnectOutcome {
        val pin = requireNotNull(DeviceIds.toFingerprint(hostId)) { "Not a device ID: $hostId" }
        val failures = mutableListOf<ConnectFailure>()

        for (address in addresses) {
            val socket = try {
                tls.connect(address, pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failures += classify(e)
                continue
            }

            // Reached the right PC: whatever happens next is the final answer, so don't try other addresses.
            val transport = SocketFrameTransport(socket)
            return try {
                when (val outcome = ClientHandshake.perform(transport, local, hostId, pairingToken)) {
                    is HandshakeOutcome.Live -> {
                        transport.setReadTimeout(0) // Liveness is the heartbeat's job from here (§9.4).
                        val session = PeerSession(transport, scope).also { it.start() }
                        ConnectOutcome.Connected(outcome.host, address, session, outcome.newlyPaired)
                    }
                    is HandshakeOutcome.PairingRejected -> ConnectOutcome.Failed(pairingFailure(outcome.reason))
                    HandshakeOutcome.ForgottenByHost -> ConnectOutcome.Failed(ConnectFailure.FORGOTTEN_BY_HOST)
                }
            } catch (e: CancellationException) {
                transport.close()
                throw e
            } catch (e: IOException) {
                transport.close()
                ConnectOutcome.Failed(classifyHandshake(e))
            }
        }
        return ConnectOutcome.Failed(summarise(failures))
    }

    /** Failures from [TlsConnector.connect]: TCP-level ones are plain IOExceptions, TLS ones are [SSLException]s. */
    private fun classify(error: IOException): ConnectFailure = when {
        error.causes().any { it is PinMismatchException } -> ConnectFailure.PIN_MISMATCH
        error is SSLException -> ConnectFailure.TLS_FAILED
        error is ConnectException -> ConnectFailure.REFUSED
        else -> ConnectFailure.UNREACHABLE // timeouts, no route to host
    }

    private fun classifyHandshake(error: IOException): ConnectFailure = when (error) {
        is ProtocolException -> if (error.code == ErrorCode.UNSUPPORTED_VERSION) ConnectFailure.UNSUPPORTED_VERSION else ConnectFailure.PROTOCOL_ERROR
        is PeerErrorException -> if (error.code == ErrorCode.UNSUPPORTED_VERSION) ConnectFailure.UNSUPPORTED_VERSION else ConnectFailure.PROTOCOL_ERROR
        is HandshakeTimeoutException -> ConnectFailure.UNREACHABLE
        // With TLS 1.3 the client finishes its handshake before the server has checked the client
        // certificate, so a host that refuses this phone shows up here, as a TLS alert or a closed
        // connection, rather than in connect(). The usual cause: the PC unpaired this phone.
        else -> ConnectFailure.TLS_FAILED
    }

    /** The most informative failure across all addresses: identity problems beat network ones. */
    private fun summarise(failures: List<ConnectFailure>): ConnectFailure = when {
        ConnectFailure.PIN_MISMATCH in failures -> ConnectFailure.PIN_MISMATCH
        ConnectFailure.TLS_FAILED in failures -> ConnectFailure.TLS_FAILED
        failures.isNotEmpty() && failures.all { it == ConnectFailure.REFUSED } -> ConnectFailure.REFUSED
        else -> ConnectFailure.UNREACHABLE
    }

    private fun pairingFailure(reason: String): ConnectFailure = when (reason) {
        "expired_token" -> ConnectFailure.TOKEN_EXPIRED
        "pairing_closed" -> ConnectFailure.PAIRING_CLOSED
        "too_many_attempts" -> ConnectFailure.TOO_MANY_ATTEMPTS
        else -> ConnectFailure.TOKEN_INVALID
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause.takeIf { c -> c !== it } }
}
