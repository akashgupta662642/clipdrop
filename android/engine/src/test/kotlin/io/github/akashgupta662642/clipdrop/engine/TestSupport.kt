package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.engine.session.FrameTransport
import io.github.akashgupta662642.clipdrop.engine.tls.TlsIdentity
import io.github.akashgupta662642.clipdrop.protocol.DeviceIds
import io.github.akashgupta662642.clipdrop.protocol.Frame
import io.github.akashgupta662642.clipdrop.protocol.Message
import io.github.akashgupta662642.clipdrop.protocol.MessageCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** EC P-256 self-signed identities, like the ones devices create on first run (PROTOCOL.md §3). */
internal object TestIdentities {
    fun create(commonName: String): TlsIdentity {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$commonName")
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + 25L * 365 * 86_400_000L), name, keyPair.public,
        )
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)))
        return TlsIdentity(keyPair.private, certificate)
    }

    fun fingerprint(identity: TlsIdentity): ByteArray = DeviceIds.fingerprint(identity.certificate.encoded)
}

/** A server-side TLS context for tests: presents [identity] and accepts client certificates per [acceptClient]. */
internal fun serverContext(
    identity: TlsIdentity,
    acceptedIssuers: Array<X509Certificate> = emptyArray(),
    acceptClient: (X509Certificate) -> Boolean,
): SSLContext {
    val password = "test".toCharArray()
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        load(null)
        setKeyEntry("host", identity.privateKey, password, arrayOf(identity.certificate))
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }.keyManagers
    val trust = object : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no client certificate")
            if (!acceptClient(leaf)) throw CertificateException("client not accepted")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
        override fun getAcceptedIssuers(): Array<X509Certificate> = acceptedIssuers
    }
    return SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trust), SecureRandom()) }
}

/**
 * An in-memory [FrameTransport]. [pair] returns two connected ends; closing one makes the other
 * read end-of-stream. [writeGate], when locked, holds writes, so tests can observe queueing.
 */
internal class PipeTransport private constructor(
    private val inbox: Channel<Frame>,
    private val outbox: Channel<Frame>,
) : FrameTransport {
    private val closed = AtomicBoolean(false)
    val writeGate = Mutex()
    val isClosed: Boolean get() = closed.get()

    override suspend fun readFrame(): Frame = inbox.receiveCatching().getOrNull() ?: throw EOFException("pipe closed")

    override suspend fun writeFrame(frame: Frame) {
        writeGate.withLock {
            if (closed.get()) throw IOException("pipe closed")
            outbox.send(frame)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            outbox.close()
            inbox.close()
        }
    }

    /** True if a frame from the other end is waiting to be read (false once closed and drained). */
    fun hasPendingFrame(): Boolean = !inbox.isEmpty

    /** Takes a waiting frame without suspending, or returns null (also when the pipe is closed). */
    fun pollFrame(): Frame? = inbox.tryReceive().getOrNull()

    suspend fun send(message: Message) = writeFrame(MessageCodec.encode(message))

    suspend fun receive(): Message = MessageCodec.decode(readFrame())

    companion object {
        fun pair(): Pair<PipeTransport, PipeTransport> {
            val aToB = Channel<Frame>(Channel.UNLIMITED)
            val bToA = Channel<Frame>(Channel.UNLIMITED)
            return PipeTransport(bToA, aToB) to PipeTransport(aToB, bToA)
        }
    }
}
