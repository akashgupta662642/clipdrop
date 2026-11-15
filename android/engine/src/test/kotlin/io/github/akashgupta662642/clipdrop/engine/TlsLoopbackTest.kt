package io.github.akashgupta662642.clipdrop.engine

import io.github.akashgupta662642.clipdrop.engine.handshake.LocalDevice
import io.github.akashgupta662642.clipdrop.engine.session.SocketFrameTransport
import io.github.akashgupta662642.clipdrop.engine.tls.PinMismatchException
import io.github.akashgupta662642.clipdrop.engine.tls.TlsConnector
import io.github.akashgupta662642.clipdrop.engine.tls.TlsIdentity
import io.github.akashgupta662642.clipdrop.protocol.DeviceIds
import io.github.akashgupta662642.clipdrop.protocol.HelloAck
import io.github.akashgupta662642.clipdrop.protocol.MessageCodec
import io.github.akashgupta662642.clipdrop.protocol.PairRequest
import io.github.akashgupta662642.clipdrop.protocol.PairResult
import io.github.akashgupta662642.clipdrop.protocol.PairingUri
import io.github.akashgupta662642.clipdrop.protocol.Ping
import io.github.akashgupta662642.clipdrop.protocol.Pong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real TLS over 127.0.0.1 between the engine's client and a JVM test server (PROTOCOL.md §4). */
class TlsLoopbackTest {
    private val host = TestIdentities.create("Test PC")
    private val phone = TestIdentities.create("Test Phone")
    private val stranger = TestIdentities.create("Someone Else")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<SSLServerSocket>()

    @AfterEach
    fun tearDown() {
        servers.forEach { it.close() }
        scope.cancel()
    }

    /** Starts a TLS server that requires a client certificate and handles each accepted socket with [onClient]. */
    private fun startServer(
        acceptClient: (X509Certificate) -> Boolean = { true },
        acceptedIssuers: Array<X509Certificate> = emptyArray(),
        protocols: Array<String>? = null,
        onClient: suspend (SSLSocket) -> Unit = { it.startHandshake() },
    ): InetSocketAddress {
        val context = serverContext(host, acceptedIssuers, acceptClient)
        val server = context.serverSocketFactory.createServerSocket(0, 10, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.needClientAuth = true
        protocols?.let { server.enabledProtocols = it }
        servers += server
        scope.launch {
            while (true) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                launch { runCatching { onClient(socket) } }
            }
        }
        return InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)
    }

    private fun connector(identity: TlsIdentity = phone) = TlsConnector(identity)

    @Test
    fun `the right pin connects over TLS 1_3 and the server sees our certificate`() = runBlocking {
        var clientSeen: ByteArray? = null
        val address = startServer(acceptClient = { clientSeen = DeviceIds.fingerprint(it.encoded); true })

        connector().connect(address, TestIdentities.fingerprint(host)).use { socket ->
            assertEquals("TLSv1.3", socket.session.protocol)
            assertTrue(DeviceIds.constantTimeEquals(TestIdentities.fingerprint(phone), clientSeen!!))
        }
    }

    @Test
    fun `TLS 1_2 still works, restricted to ECDHE-ECDSA with AES-GCM`() = runBlocking {
        val address = startServer(protocols = arrayOf("TLSv1.2"))
        connector().connect(address, TestIdentities.fingerprint(host)).use { socket ->
            assertEquals("TLSv1.2", socket.session.protocol)
            assertTrue(socket.session.cipherSuite.startsWith("TLS_ECDHE_ECDSA_WITH_AES_") && socket.session.cipherSuite.contains("GCM"))
        }
    }

    @Test
    fun `a wrong pin is rejected during the handshake`() = runBlocking {
        val address = startServer()
        val error = assertFailsWith<SSLException> { connector().connect(address, TestIdentities.fingerprint(stranger)) }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is PinMismatchException })
    }

    @Test
    fun `the client certificate is sent even when the server lists other CAs`() = runBlocking {
        var clientSeen = false
        val address = startServer(acceptedIssuers = arrayOf(stranger.certificate), acceptClient = { clientSeen = true; true })
        connector().connect(address, TestIdentities.fingerprint(host)).close()
        assertTrue(clientSeen)
    }

    @Test
    fun `a host that rejects our certificate fails the connection`() = runBlocking {
        val address = startServer(acceptClient = { false })
        val outcome = HostConnector(connector(), localDevice()).connect(listOf(address), DeviceIds.fromFingerprint(TestIdentities.fingerprint(host)), scope)
        assertEquals(ConnectOutcome.Failed(ConnectFailure.TLS_FAILED), outcome)
    }

    @Test
    fun `nothing listening is reported as refused`() = runBlocking {
        val unused = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val outcome = HostConnector(connector(), localDevice())
            .connect(listOf(InetSocketAddress(InetAddress.getLoopbackAddress(), unused)), DeviceIds.fromFingerprint(TestIdentities.fingerprint(host)), scope)
        assertEquals(ConnectOutcome.Failed(ConnectFailure.REFUSED), outcome)
    }

    @Test
    fun `pairing over real TLS, then ping`() = runBlocking {
        val hostId = DeviceIds.fromFingerprint(TestIdentities.fingerprint(host))
        val token = "AAECAwQFBgcICQoLDA0ODw"
        val address = startServer { socket ->
            socket.startHandshake()
            val transport = SocketFrameTransport(socket)
            MessageCodec.decode(transport.readFrame()) // HELLO
            transport.writeFrame(MessageCodec.encode(HelloAck(1, hostId, "Test PC", "windows", "0.1.0", paired = false)))
            val request = MessageCodec.decode(transport.readFrame()) as PairRequest
            transport.writeFrame(MessageCodec.encode(if (request.token == token) PairResult(true) else PairResult(false, "invalid_token")))
            while (true) {
                val message = MessageCodec.decode(transport.readFrame())
                if (message is Ping) transport.writeFrame(MessageCodec.encode(Pong(message.nonce)))
            }
        }

        val uri = PairingUri(listOf("127.0.0.1"), address.port, hostId, token, "Test PC")
        val connected = assertIs<ConnectOutcome.Connected>(HostConnector(connector(), localDevice()).pair(uri, scope))
        assertTrue(connected.newlyPaired)
        assertEquals("Test PC", connected.host.name)
        connected.session.ping()
        connected.session.close()
    }

    private fun localDevice() = LocalDevice(DeviceIds.fromFingerprint(TestIdentities.fingerprint(phone)), "Test Phone", "0.1.0")
}
