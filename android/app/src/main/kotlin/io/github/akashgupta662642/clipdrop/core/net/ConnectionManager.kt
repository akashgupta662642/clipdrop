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
import io.github.akashgupta662642.clipdrop.engine.ReconnectBackoff
import io.github.akashgupta662642.clipdrop.engine.handshake.LocalDevice
import io.github.akashgupta662642.clipdrop.engine.session.CloseReason
import io.github.akashgupta662642.clipdrop.engine.session.PeerSession
import io.github.akashgupta662642.clipdrop.engine.session.SessionClosedException
import io.github.akashgupta662642.clipdrop.engine.tls.TlsConnector
import io.github.akashgupta662642.clipdrop.protocol.Message
import io.github.akashgupta662642.clipdrop.protocol.PairingUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

/** A message that arrived from a paired PC. */
data class HostMessage(val hostId: String, val hostName: String, val message: Message)

/**
 * Owns one [PeerSession] per paired PC. While [startKeepingConnected] is in effect (the foreground
 * service is running), each paired PC has a loop that connects, waits for the session to end, and
 * reconnects with the backoff of PROTOCOL.md §9.6. mDNS discovery and an immediate retry when
 * Wi-Fi returns arrive in a later milestone.
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val identity: KeystoreIdentity,
    private val settings: DeviceSettings,
    private val hosts: PairedHostDao,
    private val networks: LocalNetworks,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private val maintainers = ConcurrentHashMap<String, Maintainer>()
    private val _states = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    private val _incoming = MutableSharedFlow<HostMessage>(extraBufferCapacity = 64)
    private var hostWatcher: Job? = null

    private class LiveSession(val hostName: String, val session: PeerSession)

    private class Maintainer(val job: Job, val wake: Channel<Unit>)

    /** Connection state per paired PC, keyed by device ID. PCs without an entry are [ConnectionState.Idle]. */
    val states: StateFlow<Map<String, ConnectionState>> = _states.asStateFlow()

    /** Every application message from every PC (clips now; transfers in a later milestone). */
    val incoming: SharedFlow<HostMessage> = _incoming.asSharedFlow()

    /** Names of the PCs with a live session right now. */
    val connectedHostNames: List<String> get() = sessions.values.filter { it.session.isOpen }.map { it.hostName }

    /** Pairs using a scanned QR code and keeps the resulting session open (§10.2). */
    suspend fun pair(uri: PairingUri): PairingResult =
        when (val outcome = connector().pair(uri, scope)) {
            is ConnectOutcome.Connected -> {
                val now = System.currentTimeMillis()
                hosts.upsert(
                    PairedHost(
                        deviceId = outcome.host.deviceId,
                        name = outcome.host.name,
                        lastAddress = outcome.address.hostString,
                        lastPort = outcome.address.port,
                        pairedAt = hosts.find(outcome.host.deviceId)?.pairedAt ?: now,
                        lastConnectedAt = now,
                    ),
                )
                adopt(outcome.host.deviceId, outcome.host.name, outcome.session)
                PairingResult.Paired(outcome.host.name)
            }
            is ConnectOutcome.Failed -> PairingResult.Failed(
                outcome.failure,
                wrongNetwork = outcome.failure == ConnectFailure.UNREACHABLE &&
                    !Ipv4Subnets.anyOnSameSubnet(uri.hosts, networks.localAddresses()),
            )
        }

    /** Keeps every paired PC connected, now and as PCs are paired or removed. Idempotent. */
    @Synchronized
    fun startKeepingConnected() {
        if (hostWatcher?.isActive == true) return
        hostWatcher = scope.launch {
            hosts.observeAll().collect { paired ->
                val ids = paired.map { it.deviceId }.toSet()
                (maintainers.keys - ids).forEach { maintainers.remove(it)?.job?.cancel() }
                (ids - maintainers.keys).forEach { id -> maintainers[id] = maintain(id) }
            }
        }
    }

    /** Stops reconnecting and closes every session. */
    @Synchronized
    fun stopKeepingConnected() {
        hostWatcher?.cancel()
        hostWatcher = null
        maintainers.values.forEach { it.job.cancel() }
        maintainers.clear()
        sessions.values.forEach { it.session.close() }
    }

    /** Skips the remaining backoff wait for [hostId] and tries now (the Reconnect button). */
    fun reconnectNow(hostId: String) {
        maintainers[hostId]?.wake?.trySend(Unit)
    }

    /** Sends [message] to every connected PC; returns the names of the PCs it was queued for. */
    suspend fun sendToAll(message: Message): List<String> =
        sessions.values.mapNotNull { live ->
            try {
                live.session.send(message)
                live.hostName
            } catch (_: SessionClosedException) {
                null
            }
        }

    /** Pings a connected PC; returns the round-trip time in milliseconds, or null if it didn't answer. */
    suspend fun ping(hostId: String): Long? {
        val session = sessions[hostId]?.session ?: return null
        val rtt = withTimeoutOrNull(PING_TIMEOUT) { runCatching { session.ping() }.getOrNull() } ?: return null
        setState(hostId, ConnectionState.Connected(rtt.inWholeMilliseconds))
        return rtt.inWholeMilliseconds
    }

    /** Connect, wait for the session to end, back off, repeat, until cancelled or the PC forgets us. */
    private fun maintain(hostId: String): Maintainer {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val job = scope.launch {
            val backoff = ReconnectBackoff()
            while (isActive) {
                val host = hosts.find(hostId) ?: break
                // A session may already exist, for example straight after pairing.
                val live = sessions[hostId]?.session?.takeIf { it.isOpen } ?: connectOnce(host)
                if (live != null) {
                    backoff.reset()
                    if (live.closeReason.await() == CloseReason.Unpaired) break
                    // Replaced by a newer session (the phone re-paired, say): watch that one instead.
                    if (sessions[hostId]?.session?.isOpen == true) continue
                } else if ((_states.value[hostId] as? ConnectionState.Failed)?.failure == ConnectFailure.FORGOTTEN_BY_HOST) {
                    break // Retrying can't help; the user has to pair again.
                }
                withTimeoutOrNull(backoff.nextDelay()) { wake.receive() }
            }
        }
        return Maintainer(job, wake)
    }

    private suspend fun connectOnce(host: PairedHost): PeerSession? {
        setState(host.deviceId, ConnectionState.Connecting)
        val address = InetSocketAddress(host.lastAddress, host.lastPort)
        return when (val outcome = connector().connect(listOf(address), host.deviceId, scope)) {
            is ConnectOutcome.Connected -> {
                hosts.upsert(host.copy(name = outcome.host.name, lastConnectedAt = System.currentTimeMillis()))
                adopt(host.deviceId, outcome.host.name, outcome.session)
                outcome.session
            }
            is ConnectOutcome.Failed -> {
                setState(host.deviceId, ConnectionState.Failed(outcome.failure))
                null
            }
        }
    }

    private fun adopt(hostId: String, hostName: String, session: PeerSession) {
        val live = LiveSession(hostName, session)
        sessions.put(hostId, live)?.session?.close()
        setState(hostId, ConnectionState.Connected())
        scope.launch { session.incoming.collect { _incoming.emit(HostMessage(hostId, hostName, it)) } }
        scope.launch {
            val reason = session.closeReason.await()
            sessions.remove(hostId, live) // only if a newer session hasn't replaced it
            if (reason == CloseReason.Unpaired) {
                hosts.delete(hostId) // §10.4: the PC unpaired this phone.
                _states.update { it - hostId }
            } else if (sessions[hostId] == null) {
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
