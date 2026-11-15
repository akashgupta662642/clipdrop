package io.github.akashgupta662642.clipdrop.engine.tls

import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/**
 * TLS versions and cipher suites from PROTOCOL.md §4.2. The phone is always the TLS client, so it
 * is the side that can restrict what gets negotiated; the Windows host checks the outcome.
 */
public object TlsPolicy {
    public val PROTOCOLS: List<String> = listOf("TLSv1.3", "TLSv1.2")

    public val CIPHER_SUITES: List<String> = listOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    )

    /** Enables only the allowed versions and suites that this TLS stack supports. */
    public fun configure(socket: SSLSocket) {
        val protocols = PROTOCOLS.filter { it in socket.supportedProtocols }
        val suites = CIPHER_SUITES.filter { it in socket.supportedCipherSuites }
        check(protocols.isNotEmpty() && suites.isNotEmpty()) { "This TLS stack supports none of the allowed versions or suites" }
        socket.enabledProtocols = protocols.toTypedArray()
        socket.enabledCipherSuites = suites.toTypedArray()
        socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = null }
    }

    /** Rejects a negotiated session that falls outside the policy. */
    public fun check(session: SSLSession) {
        if (session.protocol !in PROTOCOLS || session.cipherSuite !in CIPHER_SUITES) {
            throw SSLHandshakeException("Negotiated ${session.protocol} / ${session.cipherSuite} is outside the TLS policy")
        }
    }
}
