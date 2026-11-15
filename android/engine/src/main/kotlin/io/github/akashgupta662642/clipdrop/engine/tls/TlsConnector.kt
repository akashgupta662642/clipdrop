package io.github.akashgupta662642.clipdrop.engine.tls

import io.github.akashgupta662642.clipdrop.protocol.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlin.time.Duration

/**
 * Opens mutually authenticated TLS connections to a host, pinned to its fingerprint.
 *
 * @param socketFactory where plain sockets come from. On Android this is the Wi-Fi network's
 *   factory, so traffic can't leak onto mobile data.
 */
public class TlsConnector(
    private val identity: TlsIdentity,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeout: Duration = ProtocolConstants.CONNECT_TIMEOUT,
    private val handshakeTimeout: Duration = ProtocolConstants.HANDSHAKE_TIMEOUT,
) {
    /**
     * Connects, completes the TLS handshake and runs the post-handshake checks of §4.3. The returned
     * socket still has the handshake read timeout set; the caller clears it once `HELLO` is done.
     */
    public suspend fun connect(address: InetSocketAddress, hostFingerprint: ByteArray): SSLSocket =
        runInterruptible(Dispatchers.IO) { connectBlocking(address, hostFingerprint) }

    /**
     * @throws java.io.IOException from the TCP connect (refused, timed out, no route), or
     *   [SSLException] for anything that fails once TCP is up, so callers can tell "not reachable"
     *   from "reached, but TLS failed". A pin mismatch is an [SSLException] caused by [PinMismatchException].
     */
    private fun connectBlocking(address: InetSocketAddress, hostFingerprint: ByteArray): SSLSocket {
        val raw: Socket = socketFactory.createSocket()
        var tcpConnected = false
        try {
            raw.tcpNoDelay = true
            raw.connect(address, connectTimeout.inWholeMilliseconds.toInt())
            tcpConnected = true

            val trustManager = PinnedTrustManager(hostFingerprint)
            // A fresh context per connection: no session cache, so no resumption across pins.
            val context = SSLContext.getInstance("TLS").apply {
                // null = the platform's default SecureRandom.
                init(arrayOf(FixedIdentityKeyManager(identity)), arrayOf(trustManager), null)
            }
            // An IP-literal host name means no SNI is sent (§4.3).
            val socket = context.socketFactory.createSocket(raw, address.address.hostAddress, address.port, true) as SSLSocket
            socket.useClientMode = true
            TlsPolicy.configure(socket)
            socket.soTimeout = handshakeTimeout.inWholeMilliseconds.toInt()
            socket.startHandshake()

            trustManager.verifyPeer(socket.session)
            TlsPolicy.check(socket.session)
            return socket
        } catch (e: Throwable) {
            runCatching { raw.close() }
            if (tcpConnected && e !is SSLException) throw SSLException("TLS handshake failed", e)
            throw e
        }
    }
}
