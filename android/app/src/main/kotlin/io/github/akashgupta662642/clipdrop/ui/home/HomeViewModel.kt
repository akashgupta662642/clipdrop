package io.github.akashgupta662642.clipdrop.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akashgupta662642.clipdrop.core.net.ConnectionManager
import io.github.akashgupta662642.clipdrop.core.net.ConnectionState
import io.github.akashgupta662642.clipdrop.data.PairedHostDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HostItem(val deviceId: String, val name: String, val address: String, val state: ConnectionState)

data class HomeUiState(
    val loading: Boolean = true,
    val hosts: List<HostItem> = emptyList(),
    /** Name of a PC that just failed to answer a ping, shown once. */
    val pingFailedFor: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connections: ConnectionManager,
    private val hosts: PairedHostDao,
) : ViewModel() {
    private val pingFailedFor = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HomeUiState> =
        combine(hosts.observeAll(), connections.states, pingFailedFor) { paired, states, failed ->
            HomeUiState(
                loading = false,
                hosts = paired.map { host ->
                    HostItem(host.deviceId, host.name, "${host.lastAddress}:${host.lastPort}", states[host.deviceId] ?: ConnectionState.Idle)
                },
                pingFailedFor = failed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Called whenever the screen becomes visible. */
    fun onVisible() = connections.connectAll()

    fun reconnect(deviceId: String) {
        viewModelScope.launch { hosts.find(deviceId)?.let(connections::connect) }
    }

    fun ping(deviceId: String) {
        viewModelScope.launch {
            if (connections.ping(deviceId) == null) {
                pingFailedFor.value = uiState.value.hosts.firstOrNull { it.deviceId == deviceId }?.name
            }
        }
    }

    fun onPingMessageShown() = pingFailedFor.update { null }
}
