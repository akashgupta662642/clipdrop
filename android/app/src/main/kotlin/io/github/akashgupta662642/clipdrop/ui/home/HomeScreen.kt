package io.github.akashgupta662642.clipdrop.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    val context = LocalContext.current
    val localNetwork = rememberLocalNetworkPermission()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (localNetwork.granted) viewModel.onVisible() }

    // Notifications (Android 13+): optional, but the status and the Send action live there.
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }

    val snackbar = remember { SnackbarHostState() }
    val messageText = when (val message = state.message) {
        is HomeMessage.PingFailed -> stringResource(R.string.home_ping_failed, message.hostName)
        is HomeMessage.ClipboardSent -> message.result.message(context)
        null -> null
    }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.onMessageShown()
        }
    }

    HomeContent(
        state = state,
        snackbar = snackbar,
        localNetworkGranted = localNetwork.granted,
        onGrantLocalNetwork = localNetwork.request,
        notificationsGranted = notificationsGranted,
        onGrantNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onPair = onPair,
        onSendClipboard = viewModel::sendClipboard,
        onAutoSyncChange = viewModel::setAutoSyncFromPc,
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
    notificationsGranted: Boolean,
    onGrantNotifications: () -> Unit,
    onPair: () -> Unit,
    onSendClipboard: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!localNetworkGranted) {
                PermissionCard(stringResource(R.string.local_network_rationale), stringResource(R.string.local_network_grant), onGrantLocalNetwork)
            } else if (!notificationsGranted && state.hosts.isNotEmpty()) {
                PermissionCard(stringResource(R.string.home_notifications_rationale), stringResource(R.string.home_notifications_grant), onGrantNotifications)
            }
            when {
                state.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.hosts.isEmpty() -> EmptyState(Modifier.weight(1f))
                else -> {
                    FilledTonalButton(onClick = onSendClipboard, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_send_clipboard))
                    }
                    AutoSyncRow(state.autoSyncFromPc, onAutoSyncChange)
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
private fun PermissionCard(rationale: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rationale, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun AutoSyncRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_auto_sync), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.home_auto_sync_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The whole row is the toggle, so the switch itself has no separate click target.
        Switch(checked = checked, onCheckedChange = null)
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
            notificationsGranted = true,
            onGrantNotifications = {},
            onPair = {},
            onSendClipboard = {},
            onAutoSyncChange = {},
            onPing = {},
            onReconnect = {},
        )
    }
}
