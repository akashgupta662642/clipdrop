package io.github.akashgupta662642.clipdrop.core.net

import io.github.akashgupta662642.clipdrop.BuildConfig
import io.github.akashgupta662642.clipdrop.core.crypto.KeystoreIdentity
import io.github.akashgupta662642.clipdrop.data.DeviceSettings
import io.github.akashgupta662642.clipdrop.data.PairedHost
import io.github.akashgupta662642.clipdrop.data.PairedHostDao
import io.github.akashgupta662642.clipdrop.di.ApplicationScope
import io.github.akashgupta662642.clipdrop.engine.ConnectFailure
import io.github.akashgupta662642.clipdrop.engine.ConnectOutcome
import io.github.akashgupta662642.clipdrop.engine.HostConnector
import io.github.akashgupta662642.clipdrop.engine.Ipv4Subnets
import io.github.akashgupta662642.clipdrop.engine.handshake.LocalDevice
import io.github.akashgupta662642.clipdrop.engine.session.CloseReason
import io.github.akashgupta662642.clipdrop.engine.session.PeerSession
import io.github.akashgupta662642.clipdrop.engine.tls.TlsConnector
import io.github.akashgupta662642.clipdrop.protocol.PairingUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** The connection to one paired PC, as the UI sees it. */
sealed interface ConnectionState {
    data object Idle : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(val lastRoundTripMs: Long? = null) : ConnectionState

    data class Failed(val failure: ConnectFailure) : ConnectionState
}

sealed interface PairingResult {
    data class Paired(val hostName: String) : PairingResult

    /** [wrongNetwork]: no address in the QR code is on this phone's subnet (§10.3). */
    data class Failed(val failure: ConnectFailure, val wrongNetwork: Boolean) : PairingResult
}

/**
 * Owns one [PeerSession] per paired PC. This milestone connects on demand (when the app opens, after
 * pairing, or on request); the foreground service, mDNS discovery and automatic reconnect with backoff
 * build on this in later milestones.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val identity: KeystoreIdentity,
    private val settings: DeviceSettings,
    private val hosts: PairedHostDao,
    private val networks: LocalNetworks,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val connecting = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())

    /** Connection state per paired PC, keyed by device ID. PCs without an entry are [ConnectionState.Idle]. */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    /** Pairs using a scanned QR code and keeps the resulting session open (§10.2). */
    suspend fun pair(uri: PairingUri): PairingResult =
        when (val outcome = connector().pair(uri, scope)) {
            is ConnectOutcome.Connected -> {
                val now = System.currentTimeMillis()
                val existing = hosts.find(outcome.host.deviceId)
                hosts.upsert(
                    PairedHost(
                        deviceId = outcome.host.deviceId,
                        name = outcome.host.name,
                        lastAddress = outcome.address.hostString,
                        lastPort = outcome.address.port,
                        pairedAt = existing?.pairedAt ?: now,
                        lastConnectedAt = now,
                    ),
                )
                adopt(outcome.host.deviceId, outcome.session)
                PairingResult.Paired(outcome.host.name)
            }
            is ConnectOutcome.Failed -> PairingResult.Failed(
                outcome.failure,
                wrongNetwork = outcome.failure == ConnectFailure.UNREACHABLE &&
                    !Ipv4Subnets.anyOnSameSubnet(uri.hosts, networks.localAddresses()),
            )
        }

    /** Connects to every paired PC that isn't connected or connecting already. */
    fun connectAll() {
        scope.launch { hosts.all().forEach(::connect) }
    }

    fun connect(host: PairedHost) {
        if (sessions[host.deviceId]?.isOpen == true || connecting[host.deviceId]?.isActive == true) return
        connecting[host.deviceId] = scope.launch {
            setState(host.deviceId, ConnectionState.Connecting)
            val address = InetSocketAddress(host.lastAddress, host.lastPort)
            when (val outcome = connector().connect(listOf(address), host.deviceId, scope)) {
                is ConnectOutcome.Connected -> {
                    hosts.upsert(host.copy(name = outcome.host.name, lastConnectedAt = System.currentTimeMillis()))
                    adopt(host.deviceId, outcome.session)
                }
                is ConnectOutcome.Failed -> setState(host.deviceId, ConnectionState.Failed(outcome.failure))
            }
        }
    }

    /** Pings a connected PC; returns the round-trip time in milliseconds, or null if it didn't answer. */
    suspend fun ping(hostId: String): Long? {
        val session = sessions[hostId] ?: return null
        val rtt = withTimeoutOrNull(PING_TIMEOUT) { runCatching { session.ping() }.getOrNull() } ?: return null
        setState(hostId, ConnectionState.Connected(rtt.inWholeMilliseconds))
        return rtt.inWholeMilliseconds
    }

    private fun adopt(hostId: String, session: PeerSession) {
        sessions.put(hostId, session)?.close()
        setState(hostId, ConnectionState.Connected())
        scope.launch {
            val reason = session.closeReason.await()
            sessions.remove(hostId, session)
            if (reason == CloseReason.Unpaired) {
                hosts.delete(hostId) // §10.4: the PC unpaired this phone.
                _states.update { it - hostId }
            } else {
                setState(hostId, ConnectionState.Idle)
            }
        }
    }

    private suspend fun connector(): HostConnector {
        val name = settings.deviceName()
        val tlsIdentity = identity.get(name)
        return HostConnector(
            TlsConnector(tlsIdentity, networks.socketFactory()),
            LocalDevice(tlsIdentity.deviceId, name, BuildConfig.VERSION_NAME),
        )
    }

    private fun setState(hostId: String, state: ConnectionState) {
        _states.update { it + (hostId to state) }
    }

    private companion object {
        val PING_TIMEOUT = 5.seconds
    }
}
