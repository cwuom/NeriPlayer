package moe.ouom.neriplayer.core.player.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Owns the HyperOS Focus notification as a separate special-use foreground service.
 *
 * HyperOS rejects the Focus payload when it is posted as an ordinary notification from the
 * playback process. Keeping this notification in a dedicated lyric FGS mirrors the lifecycle
 * expected by the Super Island renderer without changing the app's media-session notification.
 */
internal class XiaomiSuperIslandLyricService : Service() {
    companion object {
        const val TAG = "NeriPlayerSuperIslandSvc"
        const val ACTION_PUBLISH = "moe.ouom.neriplayer.action.PUBLISH_SUPER_ISLAND_LYRIC"
        const val ACTION_STOP = "moe.ouom.neriplayer.action.STOP_SUPER_ISLAND_LYRIC"
        const val NOTIFICATION_ID = 0x454c4c53

        @Volatile
        private var pendingNotification: Notification? = null

        @Volatile
        private var serviceRunning = false

        fun publish(context: Context, notification: Notification) {
            // Notification can contain a full-size album bitmap. Passing it through an Intent
            // exceeds Binder's 1 MB transaction limit on real tracks, so the same-process
            // service receives it from this in-memory hand-off instead.
            pendingNotification = notification
            val intent = Intent(context, XiaomiSuperIslandLyricService::class.java)
                .setAction(ACTION_PUBLISH)
            val result = runCatching {
                if (serviceRunning) {
                    // Once the special-use FGS exists, startForegroundService on every lyric
                    // update needlessly re-enters the system's FGS start path.
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            }.recoverCatching {
                // A ROM may have stopped the service between two lyric ticks. Re-enter through
                // the foreground path instead of dropping the island update.
                serviceRunning = false
                ContextCompat.startForegroundService(context, intent)
            }
            if (result.isFailure) {
                result.exceptionOrNull()?.let { error ->
                    Log.w(TAG, "Unable to start Super Island lyric foreground service", error)
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification)
            }
        }

        fun stop(context: Context) {
            serviceRunning = false
            val intent = Intent(context, XiaomiSuperIslandLyricService::class.java)
                .setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { error -> Log.w(TAG, "Unable to stop Super Island lyric service", error) }
        }
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUBLISH -> pendingNotification?.let(::publish)
                ?: Log.w(TAG, "No Super Island lyric notification available to publish")
            ACTION_STOP -> stopLyricForeground()
        }
        return START_NOT_STICKY
    }

    private fun publish(notification: Notification) {
        if (!foregroundStarted) {
            val startedWithLyric = runCatching {
                // The Focus payload is already a valid foreground notification. Posting it
                // directly avoids the old warm-notification -> Focus-notification pair, which
                // made SystemUI inflate the same island twice on its first appearance.
                startForegroundWithType(notification)
                foregroundStarted = true
                Log.d(TAG, "Super Island lyric foreground service started with Focus payload")
            }.isSuccess
            if (!startedWithLyric) {
                // Preserve the previous best-effort rendering path if this particular ROM rejects
                // a Focus payload during startForeground. The fallback still keeps the island alive,
                // but it is only used on ROMs that require the warm notification handshake.
                runCatching {
                    startForegroundWithType(buildWarmNotification())
                    foregroundStarted = true
                    Log.d(TAG, "Super Island lyric foreground service started with warm payload")
                }.onFailure { error ->
                    Log.w(TAG, "Unable to enter Super Island lyric foreground service", error)
                }
            }
        }
        // Keep an explicit notify() after startForeground(). Some HyperOS builds accept the
        // foreground notification but do not pass its Focus extras to SystemUI until the same
        // notification is updated through NotificationManager.
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildWarmNotification(): Notification =
        Notification.Builder(this, XiaomiSuperIslandLyricBridge.CHANNEL_ID)
            .setSmallIcon(moe.ouom.neriplayer.R.drawable.ic_lyrics_24)
            .setContentTitle("")
            .setContentText("")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()

    private fun stopLyricForeground() {
        foregroundStarted = false
        serviceRunning = false
        pendingNotification = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceRunning = false
        foregroundStarted = false
        pendingNotification = null
        super.onDestroy()
    }
}
