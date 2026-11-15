package io.github.akashgupta662642.clipdrop.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akashgupta662642.clipdrop.R
import io.github.akashgupta662642.clipdrop.core.net.ConnectionState
import io.github.akashgupta662642.clipdrop.ui.message
import io.github.akashgupta662642.clipdrop.ui.rememberLocalNetworkPermission
import io.github.akashgupta662642.clipdrop.ui.theme.ClipDropTheme

@Composable
fun HomeScreen(onPair: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val localNetwork = rememberLocalNetworkPermission()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (localNetwork.granted) viewModel.onVisible() }

    val snackbar = remember { SnackbarHostState() }
    val pingFailedMessage = state.pingFailedFor?.let { stringResource(R.string.home_ping_failed, it) }
    LaunchedEffect(pingFailedMessage) {
        if (pingFailedMessage != null) {
            snackbar.showSnackbar(pingFailedMessage)
            viewModel.onPingMessageShown()
        }
    }

    HomeContent(
        state = state,
        snackbar = snackbar,
        localNetworkGranted = localNetwork.granted,
        onGrantLocalNetwork = localNetwork.request,
        onPair = onPair,
        onPing = viewModel::ping,
        onReconnect = viewModel::reconnect,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    snackbar: SnackbarHostState,
    localNetworkGranted: Boolean,
    onGrantLocalNetwork: () -> Unit,
    onPair: () -> Unit,
    onPing: (String) -> Unit,
    onReconnect: (String) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!localNetworkGranted) {
                Text(stringResource(R.string.local_network_rationale), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onGrantLocalNetwork) { Text(stringResource(R.string.local_network_grant)) }
            }
            when {
                state.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.hosts.isEmpty() -> EmptyState(Modifier.weight(1f))
                else -> {
                    Text(stringResource(R.string.home_section_pcs), style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.hosts, key = { it.deviceId }) { host -> HostCard(host, onPing, onReconnect) }
                    }
                }
            }
            Button(onClick = onPair, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(stringResource(R.string.home_pair_button))
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HostCard(host: HostItem, onPing: (String) -> Unit, onReconnect: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(host.name, style = MaterialTheme.typography.titleMedium)
            Text(statusText(host.state), style = MaterialTheme.typography.bodyMedium)
            Text(host.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (host.state) {
                    is ConnectionState.Connected -> OutlinedButton(onClick = { onPing(host.deviceId) }) { Text(stringResource(R.string.home_ping)) }
                    ConnectionState.Connecting -> Unit
                    else -> OutlinedButton(onClick = { onReconnect(host.deviceId) }) { Text(stringResource(R.string.home_reconnect)) }
                }
            }
        }
    }
}

@Composable
private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> stringResource(R.string.state_idle)
    ConnectionState.Connecting -> stringResource(R.string.state_connecting)
    is ConnectionState.Connected -> state.lastRoundTripMs?.let { stringResource(R.string.state_connected_rtt, it) }
        ?: stringResource(R.string.state_connected)
    is ConnectionState.Failed -> stringResource(state.failure.message())
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    ClipDropTheme {
        HomeContent(
            state = HomeUiState(
                loading = false,
                hosts = listOf(
                    HostItem("a", "Office PC", "192.168.1.20:47821", ConnectionState.Connected(4)),
                    HostItem("b", "Laptop", "10.0.0.7:47821", ConnectionState.Idle),
                ),
            ),
            snackbar = SnackbarHostState(),
            localNetworkGranted = true,
            onGrantLocalNetwork = {},
            onPair = {},
            onPing = {},
            onReconnect = {},
        )
    }
}
