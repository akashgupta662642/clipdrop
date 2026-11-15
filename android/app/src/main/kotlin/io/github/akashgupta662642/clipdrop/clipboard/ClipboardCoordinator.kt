package io.github.akashgupta662642.clipdrop.clipboard

import io.github.akashgupta662642.clipdrop.core.net.ConnectionManager
import io.github.akashgupta662642.clipdrop.data.DeviceSettings
import io.github.akashgupta662642.clipdrop.di.ApplicationScope
import io.github.akashgupta662642.clipdrop.engine.clipboard.ClipboardSync
import io.github.akashgupta662642.clipdrop.engine.clipboard.LocalClip
import io.github.akashgupta662642.clipdrop.engine.clipboard.OutgoingClip
import io.github.akashgupta662642.clipdrop.protocol.ClipText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of sending the phone's clipboard, for a toast or snackbar. */
sealed interface SendResult {
    data class Sent(val hostNames: List<String>) : SendResult

    data object NoPcConnected : SendResult

    data class Skipped(val reason: OutgoingClip.Skipped) : SendResult
}

/**
 * Connects the clipboard to the sessions (PROTOCOL.md §11): PC clips are written automatically
 * (when enabled), and phone clips are sent on request. Created at app start, so clips arrive
 * whenever the foreground service holds a connection.
 */
@Singleton
class ClipboardCoordinator @Inject constructor(
    private val connections: ConnectionManager,
    private val bridge: ClipboardBridge,
    private val settings: DeviceSettings,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val sync = ClipboardSync()

    init {
        scope.launch {
            connections.incoming.collect { incoming ->
                (incoming.message as? ClipText)?.let { onClipFromPc(it) }
            }
        }
    }

    /** Sends a clip the user chose to send (tile, notification, or in-app button). */
    suspend fun send(clip: LocalClip): SendResult =
        when (val outgoing = sync.prepare(clip)) {
            is OutgoingClip.Skipped -> SendResult.Skipped(outgoing)
            is OutgoingClip.Send -> {
                val sentTo = connections.sendToAll(outgoing.message)
                if (sentTo.isEmpty()) SendResult.NoPcConnected else SendResult.Sent(sentTo)
            }
        }

    /** [send] on the app scope, for callers that are about to finish; [onResult] runs on the main thread. */
    fun sendInBackground(clip: LocalClip, onResult: (SendResult) -> Unit) {
        scope.launch {
            val result = send(clip)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private suspend fun onClipFromPc(clip: ClipText) {
        // Always remember it, so even an ignored clip isn't sent straight back later.
        sync.onReceived(clip)
        if (!settings.autoSyncFromPc.first()) return
        withContext(Dispatchers.Main) { bridge.write(clip.text) }
    }
}
