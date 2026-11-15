package io.github.akashgupta662642.clipdrop.ui.pairing

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.akashgupta662642.clipdrop.core.net.ConnectionManager
import io.github.akashgupta662642.clipdrop.core.net.PairingResult
import io.github.akashgupta662642.clipdrop.engine.ConnectFailure
import io.github.akashgupta662642.clipdrop.protocol.PairingUri
import io.github.akashgupta662642.clipdrop.ui.PairRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PairUiState {
    /** The QR code is valid; waiting for the user to confirm. */
    data class Confirm(val hostName: String) : PairUiState

    data class Pairing(val hostName: String) : PairUiState

    data class Paired(val hostName: String) : PairUiState

    data class Failed(val failure: ConnectFailure, val wrongNetwork: Boolean) : PairUiState

    /** Not a ClipDrop pairing URI, or a malformed one. */
    data object InvalidCode : PairUiState

    /** A well-formed URI from a newer ClipDrop (§6). */
    data object NewerVersion : PairUiState
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val connections: ConnectionManager,
) : ViewModel() {
    private val uri: PairingUri?
    private val _uiState: MutableStateFlow<PairUiState>
    val uiState: StateFlow<PairUiState>

    init {
        val parsed = PairingUri.parse(savedStateHandle.toRoute<PairRoute>().uri)
        uri = (parsed as? PairingUri.ParseResult.Valid)?.uri
        _uiState = MutableStateFlow(
            when (parsed) {
                is PairingUri.ParseResult.Valid -> PairUiState.Confirm(parsed.uri.name)
                PairingUri.ParseResult.UnsupportedVersion -> PairUiState.NewerVersion
                is PairingUri.ParseResult.Invalid -> PairUiState.InvalidCode
            },
        )
        uiState = _uiState.asStateFlow()
    }

    /** Runs pairing; safe to call again after a failure ("Try again" while the code is still valid). */
    fun pair() {
        val uri = uri ?: return
        if (_uiState.value is PairUiState.Pairing) return
        _uiState.value = PairUiState.Pairing(uri.name)
        viewModelScope.launch {
            _uiState.value = when (val result = connections.pair(uri)) {
                is PairingResult.Paired -> PairUiState.Paired(result.hostName)
                is PairingResult.Failed -> PairUiState.Failed(result.failure, result.wrongNetwork)
            }
        }
    }
}
