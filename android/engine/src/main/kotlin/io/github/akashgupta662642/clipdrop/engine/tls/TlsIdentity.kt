package io.github.akashgupta662642.clipdrop.engine.tls

import io.github.akashgupta662642.clipdrop.protocol.DeviceIds
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * This device's identity (PROTOCOL.md §3): the private key and its self-signed certificate. On
 * Android the key is an opaque AndroidKeyStore handle; in the JVM harness it comes from PKCS#12.
 */
public class TlsIdentity(public val privateKey: PrivateKey, public val certificate: X509Certificate) {
    public val deviceId: String = DeviceIds.deviceId(certificate.encoded)
}

/**
 * Always presents [identity] as the client certificate. The default key manager would filter by the
 * server's list of acceptable CAs, and a self-signed certificate is never on it (§4.3).
 */
public class FixedIdentityKeyManager(private val identity: TlsIdentity) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == ALIAS) arrayOf(identity.certificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) identity.privateKey else null

    private companion object {
        const val ALIAS = "clipdrop-identity"
    }
}
