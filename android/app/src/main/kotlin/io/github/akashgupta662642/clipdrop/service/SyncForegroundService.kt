package io.github.akashgupta662642.clipdrop.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.akashgupta662642.clipdrop.MainActivity
import io.github.akashgupta662642.clipdrop.R
import io.github.akashgupta662642.clipdrop.clipboard.SendClipboardActivity
import io.github.akashgupta662642.clipdrop.core.net.ConnectionManager
import io.github.akashgupta662642.clipdrop.core.net.ConnectionState
import io.github.akashgupta662642.clipdrop.data.PairedHostDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the connections to paired PCs alive while the app isn't on screen, so clips copied on a
 * PC land on the phone automatically. Type connectedDevice: no daily time limit, and on Android 14+
 * its prerequisite is met by the CHANGE_WIFI_MULTICAST_STATE permission. Start it only while the
 * app is in the foreground.
 */
@AndroidEntryPoint
class SyncForegroundService : Service() {
    @Inject
    lateinit var connections: ConnectionManager

    @Inject
    lateinit var hosts: PairedHostDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusUpdates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            connections.stopKeepingConnected()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.state_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        connections.startKeepingConnected()
        if (statusUpdates == null) {
            statusUpdates = scope.launch {
                combine(hosts.observeAll(), connections.states) { paired, states -> statusText(paired.map { it.deviceId to it.name }, states) }
                    .distinctUntilChanged()
                    .collect(::showStatus)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        connections.stopKeepingConnected()
        super.onDestroy()
    }

    private fun statusText(paired: List<Pair<String, String>>, states: Map<String, ConnectionState>): String {
        val connected = paired.filter { states[it.first] is ConnectionState.Connected }.map { it.second }
        return when {
            connected.isNotEmpty() -> getString(R.string.notification_connected, connected.joinToString(", "))
            paired.any { states[it.first] == ConnectionState.Connecting } -> getString(R.string.state_connecting)
            else -> getString(R.string.state_idle)
        }
    }

    private fun showStatus(text: String) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val send = PendingIntent.getActivity(this, 1, SendClipboardActivity.intent(this), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_clipdrop)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_send_clipboard), send)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_connection), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.akashgupta662642.clipdrop.action.STOP"

        /** Starts (or keeps) the service. Call only while the app is in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncForegroundService::class.java))
        }
    }
}

/** Starts the service from places that shouldn't hold a Context, such as ViewModels. */
@Singleton
class SyncServiceController @Inject constructor(@ApplicationContext private val context: Context) {
    /** Starts (or keeps) the service. Call only while the app is in the foreground. */
    fun start() = SyncForegroundService.start(context)
}
