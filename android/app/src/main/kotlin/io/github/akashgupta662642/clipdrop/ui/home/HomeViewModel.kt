package io.github.akashgupta662642.clipdrop.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akashgupta662642.clipdrop.clipboard.ClipboardBridge
import io.github.akashgupta662642.clipdrop.clipboard.ClipboardCoordinator
import io.github.akashgupta662642.clipdrop.clipboard.SendResult
import io.github.akashgupta662642.clipdrop.core.net.ConnectionManager
import io.github.akashgupta662642.clipdrop.core.net.ConnectionState
import io.github.akashgupta662642.clipdrop.data.DeviceSettings
import io.github.akashgupta662642.clipdrop.data.PairedHostDao
import io.github.akashgupta662642.clipdrop.service.SyncServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HostItem(val deviceId: String, val name: String, val address: String, val state: ConnectionState)

/** A one-off message for the snackbar. */
sealed interface HomeMessage {
    data class PingFailed(val hostName: String) : HomeMessage

    data class ClipboardSent(val result: SendResult) : HomeMessage
}

data class HomeUiState(
    val loading: Boolean = true,
    val hosts: List<HostItem> = emptyList(),
    val autoSyncFromPc: Boolean = true,
    val message: HomeMessage? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val service: SyncServiceController,
    private val connections: ConnectionManager,
    private val clipboard: ClipboardCoordinator,
    private val bridge: ClipboardBridge,
    private val hosts: PairedHostDao,
    private val settings: DeviceSettings,
) : ViewModel() {
    private val message = MutableStateFlow<HomeMessage?>(null)

    val uiState: StateFlow<HomeUiState> =
        combine(hosts.observeAll(), connections.states, settings.autoSyncFromPc, message) { paired, states, autoSync, msg ->
            HomeUiState(
                loading = false,
                hosts = paired.map { host ->
                    HostItem(host.deviceId, host.name, "${host.lastAddress}:${host.lastPort}", states[host.deviceId] ?: ConnectionState.Idle)
                },
                autoSyncFromPc = autoSync,
                message = msg,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Called whenever the screen becomes visible, with local network access granted: starts the
     * foreground service, which keeps paired PCs connected from then on. It may only be started
     * while the app is in the foreground, which is exactly now.
     */
    fun onVisible() {
        viewModelScope.launch {
            if (hosts.all().isNotEmpty()) service.start()
        }
    }

    fun reconnect(deviceId: String) {
        service.start()
        connections.reconnectNow(deviceId)
    }

    fun ping(deviceId: String) {
        viewModelScope.launch {
            if (connections.ping(deviceId) == null) {
                val name = uiState.value.hosts.firstOrNull { it.deviceId == deviceId }?.name ?: return@launch
                message.value = HomeMessage.PingFailed(name)
            }
        }
    }

    /** Reads the clipboard now, while the screen has focus, and sends it. */
    fun sendClipboard() {
        val clip = bridge.read()
        viewModelScope.launch { message.value = HomeMessage.ClipboardSent(clipboard.send(clip)) }
    }

    fun setAutoSyncFromPc(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoSyncFromPc(enabled) }
    }

    fun onMessageShown() = message.update { null }
}
