package io.github.akashgupta662642.clipdrop.clipboard

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.akashgupta662642.clipdrop.R
import io.github.akashgupta662642.clipdrop.core.net.ConnectionManager
import javax.inject.Inject

/** Quick Settings tile: one tap sends the phone's clipboard to the connected PCs. */
@AndroidEntryPoint
class ClipboardTileService : TileService() {
    @Inject
    lateinit var connections: ConnectionManager

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val connected = connections.connectedHostNames
        tile.state = if (connected.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = connected.firstOrNull() ?: getString(R.string.state_idle)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::launchSendActivity) else launchSendActivity()
    }

    private fun launchSendActivity() {
        val intent = SendClipboardActivity.intent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // The PendingIntent overload only exists from API 34, and the Intent overload throws on
            // Android 14+ for apps targeting it, so each API range gets the one that works there.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
