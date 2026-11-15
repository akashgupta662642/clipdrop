package io.github.akashgupta662642.clipdrop.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akashgupta662642.clipdrop.R
import io.github.akashgupta662642.clipdrop.engine.ConnectFailure
import io.github.akashgupta662642.clipdrop.ui.message
import io.github.akashgupta662642.clipdrop.ui.rememberLocalNetworkPermission
import io.github.akashgupta662642.clipdrop.ui.theme.ClipDropTheme

@Composable
fun PairScreen(onDone: () -> Unit, onScanAgain: () -> Unit, viewModel: PairingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val localNetwork = rememberLocalNetworkPermission()
    PairContent(
        state = state,
        localNetworkGranted = localNetwork.granted,
        onGrantLocalNetwork = localNetwork.request,
        onPair = viewModel::pair,
        onDone = onDone,
        onScanAgain = onScanAgain,
    )
}

@Composable
private fun PairContent(
    state: PairUiState,
    localNetworkGranted: Boolean,
    onGrantLocalNetwork: () -> Unit,
    onPair: () -> Unit,
    onDone: () -> Unit,
    onScanAgain: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is PairUiState.Confirm -> {
                    Title(stringResource(R.string.pair_title, state.hostName))
                    Body(stringResource(R.string.pair_body, state.hostName))
                    if (localNetworkGranted) {
                        Buttons(stringResource(R.string.pair_confirm), onPair, stringResource(R.string.cancel), onDone)
                    } else {
                        Body(stringResource(R.string.local_network_rationale))
                        Buttons(stringResource(R.string.local_network_grant), onGrantLocalNetwork, stringResource(R.string.cancel), onDone)
                    }
                }
                is PairUiState.Pairing -> {
                    CircularProgressIndicator()
                    Body(stringResource(R.string.pair_in_progress, state.hostName))
                }
                is PairUiState.Paired -> {
                    Title(stringResource(R.string.pair_success, state.hostName))
                    Body(stringResource(R.string.pair_success_body))
                    Button(onClick = onDone) { Text(stringResource(R.string.done)) }
                }
                is PairUiState.Failed -> {
                    Title(stringResource(R.string.pair_failed_title))
                    Body(stringResource(state.failure.message(state.wrongNetwork)))
                    if (state.failure.isRetryable()) {
                        Buttons(stringResource(R.string.try_again), onPair, stringResource(R.string.scan_again), onScanAgain)
                    } else {
                        Buttons(stringResource(R.string.scan_again), onScanAgain, stringResource(R.string.cancel), onDone)
                    }
                }
                PairUiState.InvalidCode -> {
                    Title(stringResource(R.string.pair_failed_title))
                    Body(stringResource(R.string.pair_invalid_code))
                    Buttons(stringResource(R.string.scan_again), onScanAgain, stringResource(R.string.cancel), onDone)
                }
                PairUiState.NewerVersion -> {
                    Title(stringResource(R.string.pair_failed_title))
                    Body(stringResource(R.string.pair_newer_version))
                    Button(onClick = onDone) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}

/** Network problems may clear up with the same code; token problems need a fresh QR code. */
private fun ConnectFailure.isRetryable(): Boolean =
    this == ConnectFailure.UNREACHABLE || this == ConnectFailure.REFUSED || this == ConnectFailure.TLS_FAILED

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)

@Composable
private fun Buttons(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onSecondary) { Text(secondary) }
        Button(onClick = onPrimary) { Text(primary) }
    }
}

@Preview(showBackground = true)
@Composable
private fun PairConfirmPreview() {
    ClipDropTheme { PairContent(PairUiState.Confirm("Office PC"), true, {}, {}, {}, {}) }
}

@Preview(showBackground = true)
@Composable
private fun PairFailedPreview() {
    ClipDropTheme { PairContent(PairUiState.Failed(ConnectFailure.UNREACHABLE, wrongNetwork = true), true, {}, {}, {}, {}) }
}
