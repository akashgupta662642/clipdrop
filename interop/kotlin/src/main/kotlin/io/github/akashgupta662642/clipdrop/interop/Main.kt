package io.github.akashgupta662642.clipdrop.interop

import io.github.akashgupta662642.clipdrop.engine.ConnectOutcome
import io.github.akashgupta662642.clipdrop.engine.HostConnector
import io.github.akashgupta662642.clipdrop.engine.handshake.LocalDevice
import io.github.akashgupta662642.clipdrop.engine.tls.TlsConnector
import io.github.akashgupta662642.clipdrop.engine.tls.TlsIdentity
import io.github.akashgupta662642.clipdrop.protocol.PairingUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.Properties
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/*
 * clipdrop-interop: the phone side of the interop test, running the Android engine on a JVM.
 *
 *   clipdrop-interop pair --state <dir> --uri-file <path>   pair using the host's pairing URI
 *   clipdrop-interop ping --state <dir> [--count <n>]       reconnect as a paired device and ping
 *
 * Exit code 0 means success. Each step prints one line per event.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    val options = parseOptions(args.drop(1))
    val state = options["--state"]?.let(::File) ?: usage()
    state.mkdirs()

    val code = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withTimeout(60.seconds) {
                when (command) {
                    "pair" -> pair(state, File(options["--uri-file"] ?: usage()), scope)
                    "ping" -> ping(state, options["--count"]?.toInt() ?: 3, scope)
                    else -> usage()
                }
            }
        } finally {
            scope.cancel()
        }
    }
    exitProcess(code)
}

private suspend fun pair(state: File, uriFile: File, scope: CoroutineScope): Int {
    val uri = when (val parsed = PairingUri.parse(uriFile.readText().trim())) {
        is PairingUri.ParseResult.Valid -> parsed.uri
        else -> return fail("pairing URI rejected: $parsed")
    }
    val identity = loadOrCreateIdentity(state)
    println("CLIENT ${identity.deviceId}")

    return when (val outcome = connector(identity).pair(uri, scope)) {
        is ConnectOutcome.Failed -> fail("pairing failed: ${outcome.failure}")
        is ConnectOutcome.Connected -> {
            println("PAIRED ${outcome.host.deviceId} ${outcome.host.name} newlyPaired=${outcome.newlyPaired}")
            saveHost(state, outcome.host.deviceId, outcome.address)
            println("PING ${outcome.session.ping().inWholeMilliseconds} ms")
            outcome.session.close()
            0
        }
    }
}

private suspend fun ping(state: File, count: Int, scope: CoroutineScope): Int {
    val identity = loadOrCreateIdentity(state)
    val (hostId, address) = loadHost(state) ?: return fail("not paired yet: run 'pair' first")

    return when (val outcome = connector(identity).connect(listOf(address), hostId, scope)) {
        is ConnectOutcome.Failed -> fail("reconnect failed: ${outcome.failure}")
        is ConnectOutcome.Connected -> {
            check(!outcome.newlyPaired) { "a reconnect must not pair again" }
            println("CONNECTED ${outcome.host.deviceId} as an already-paired device")
            repeat(count) { println("PING ${outcome.session.ping().inWholeMilliseconds} ms") }
            outcome.session.close()
            0
        }
    }
}

private fun connector(identity: TlsIdentity) =
    HostConnector(TlsConnector(identity), LocalDevice(identity.deviceId, "Interop Client", "0.1.0", platform = "jvm"))

// ----------------------------------------------------------------------------------------- state

private val KEYSTORE_PASSWORD = "clipdrop-interop".toCharArray()

/** A PKCS#12 identity in the state directory, created on first use (the JVM stand-in for the AndroidKeyStore). */
private fun loadOrCreateIdentity(state: File): TlsIdentity {
    val file = File(state, "identity.p12")
    val keyStore = KeyStore.getInstance("PKCS12")
    if (file.exists()) {
        file.inputStream().use { keyStore.load(it, KEYSTORE_PASSWORD) }
    } else {
        val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Interop Client")
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + 25L * 365 * 86_400_000L), name, keyPair.public,
            ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)),
        )
        keyStore.load(null)
        keyStore.setKeyEntry("identity", keyPair.private, KEYSTORE_PASSWORD, arrayOf(certificate))
        file.outputStream().use { keyStore.store(it, KEYSTORE_PASSWORD) }
    }
    return TlsIdentity(
        keyStore.getKey("identity", KEYSTORE_PASSWORD) as PrivateKey,
        keyStore.getCertificate("identity") as X509Certificate,
    )
}

private fun saveHost(state: File, hostId: String, address: InetSocketAddress) {
    val properties = Properties().apply {
        setProperty("hostId", hostId)
        setProperty("address", address.hostString)
        setProperty("port", address.port.toString())
    }
    File(state, "host.properties").outputStream().use { properties.store(it, "Paired host") }
}

private fun loadHost(state: File): Pair<String, InetSocketAddress>? {
    val file = File(state, "host.properties").takeIf { it.exists() } ?: return null
    val properties = Properties().apply { file.inputStream().use { load(it) } }
    return properties.getProperty("hostId") to
        InetSocketAddress(properties.getProperty("address"), properties.getProperty("port").toInt())
}

// ------------------------------------------------------------------------------------------- cli

private fun parseOptions(args: List<String>): Map<String, String> =
    args.chunked(2).associate { (key, value) -> key to value }

private fun fail(message: String): Int {
    System.err.println("FAILED $message")
    return 1
}

private fun usage(): Nothing {
    System.err.println("Usage: clipdrop-interop pair --state <dir> --uri-file <path> | ping --state <dir> [--count <n>]")
    exitProcess(2)
}
