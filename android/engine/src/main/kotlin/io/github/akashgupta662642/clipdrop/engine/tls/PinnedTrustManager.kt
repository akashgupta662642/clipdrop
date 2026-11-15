package io.github.akashgupta662642.clipdrop.engine.tls

import io.github.akashgupta662642.clipdrop.protocol.DeviceIds
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509ExtendedTrustManager

/** The peer's certificate isn't the one we pinned. */
public class PinMismatchException(message: String) : CertificateException(message)

/**
 * Client-side trust: accepts exactly one server certificate, identified by its SHA-256
 * fingerprint (PROTOCOL.md §4.3). No trust store, chain, host name or validity-date checks:
 * two self-signed certificates can't chain to anything, and device clocks can differ.
 *
 * Extends [X509ExtendedTrustManager] so the JSSE doesn't wrap it with extra checks of its own.
 */
public class PinnedTrustManager(expectedFingerprint: ByteArray) : X509ExtendedTrustManager() {
    private val expected = expectedFingerprint.copyOf()

    init {
        require(expected.size == 32) { "A fingerprint is 32 bytes" }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit = verify(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("This trust manager only verifies servers")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit =
        throw CertificateException("This trust manager only verifies servers")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit =
        throw CertificateException("This trust manager only verifies servers")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The server presented no certificate")
        if (!matches(leaf)) throw PinMismatchException("The server certificate doesn't match the pinned fingerprint")
    }

    private fun matches(certificate: X509Certificate): Boolean =
        DeviceIds.constantTimeEquals(DeviceIds.fingerprint(certificate.encoded), expected)

    /**
     * The mandatory post-handshake check (§4.3): a resumed session may skip [checkServerTrusted],
     * so the pin is verified again against the certificate the TLS stack actually reports.
     */
    public fun verifyPeer(session: SSLSession) {
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (e: SSLPeerUnverifiedException) {
            throw PinMismatchException("The server presented no certificate").apply { initCause(e) }
        } ?: throw PinMismatchException("The server presented no X.509 certificate")
        if (!matches(leaf)) throw PinMismatchException("The server certificate doesn't match the pinned fingerprint")
    }
}
