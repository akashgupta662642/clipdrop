package io.github.akashgupta662642.clipdrop.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.akashgupta662642.clipdrop.engine.tls.TlsIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

/**
 * This phone's identity (PROTOCOL.md §3), held in the AndroidKeyStore. The private key never
 * leaves the Keystore; the Keystore also produces the self-signed certificate.
 */
@Singleton
class KeystoreIdentity @Inject constructor() {
    private val mutex = Mutex()

    @Volatile
    private var cached: TlsIdentity? = null

    /** Loads the identity, creating it on first use with [deviceName] as the certificate subject. */
    suspend fun get(deviceName: String): TlsIdentity =
        cached ?: mutex.withLock {
            cached ?: withContext(Dispatchers.IO) { loadOrCreate(deviceName) }.also { cached = it }
        }

    private fun loadOrCreate(deviceName: String): TlsIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) generate(deviceName)
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        return TlsIdentity(key, certificate)
    }

    private fun generate(deviceName: String) {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // DIGEST_NONE is required: Android's TLS stack (Conscrypt) hashes the handshake itself
            // and asks the Keystore for a raw NONEwithECDSA signature. Verified against SChannel.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=" + escapeRdnValue(deviceName.ifBlank { "ClipDrop" })))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - ONE_DAY_MS))
            .setCertificateNotAfter(Date(now + VALIDITY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "clipdrop-identity"
        const val ONE_DAY_MS = 86_400_000L
        const val VALIDITY_MS = 25L * 365 * ONE_DAY_MS

        /** RFC 4514 escaping for an attribute value, so any device name makes a valid subject. */
        fun escapeRdnValue(value: String): String = buildString {
            value.forEachIndexed { index, c ->
                val leading = index == 0 && (c == ' ' || c == '#')
                val trailing = index == value.lastIndex && c == ' '
                if (leading || trailing || c in ",+\"\\<>;=") append('\\')
                append(c)
            }
        }
    }
}
