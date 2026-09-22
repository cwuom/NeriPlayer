package moe.ouom.neriplayer.core.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry

/** Publishes current lyrics as an Android 16 Live Update-compatible notification. */
internal class LiveLyricNotificationBridge(context: Context) {
    private companion object {
        const val TAG = "LiveLyricNotification"
        const val CHANNEL_ID = "neriplayer_live_lyric_updates_v1"
        const val NOTIFICATION_ID = 0x4e504c52
        // Android throttles updates to existing notifications. Keep a small margin while still
        // allowing word/line timing sources to update without flooding NotificationManager.
        const val MIN_UPDATE_INTERVAL_MS = 220L
    }

    private data class Payload(
        val songTitle: String,
        val lyric: String,
        val compactLyric: String,
        val secondaryText: String,
        val artwork: Bitmap?
    )

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var lastPayload: Payload? = null
    private var pendingPayload: Payload? = null
    private var pendingDispatch: Runnable? = null
    private var lastDispatchElapsedMs = 0L

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            clear()
        } else {
            lastPayload = null
            lastDispatchElapsedMs = 0L
        }
    }

    fun sendLyric(
        songTitle: String?,
        line: LyricEntry,
        secondaryLyric: String? = null,
        artwork: Bitmap? = null
    ) {
        if (!enabled) return
        val resolved = buildLiveLyricNotificationText(line) ?: run {
            clear()
            return
        }
        val cleanSongTitle = songTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.app_name)
        val cleanSecondaryText = buildLiveLyricSecondaryText(secondaryLyric)
            ?: cleanSongTitle
        val payload = Payload(
            songTitle = cleanSongTitle,
            lyric = resolved.lyric,
            compactLyric = resolved.compactLyric,
            secondaryText = cleanSecondaryText,
            artwork = artwork
        )
        if (payload == lastPayload) {
            // A timing correction may bring the pending value back to the already-posted value.
            // Cancel the stale runnable so it cannot re-post an older lyric after this call.
            cancelPendingDispatch()
            return
        }
        if (payload == pendingPayload) return

        pendingPayload = payload
        dispatchPendingPayload()
    }

    fun clear() {
        cancelPendingDispatch()
        lastPayload = null
        lastDispatchElapsedMs = 0L
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun dispatchPendingPayload() {
        val payload = pendingPayload ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val remainingMs = if (lastDispatchElapsedMs == 0L) {
            0L
        } else {
            (MIN_UPDATE_INTERVAL_MS - (nowMs - lastDispatchElapsedMs)).coerceAtLeast(0L)
        }
        if (remainingMs > 0L) {
            if (pendingDispatch == null) {
                val dispatch = Runnable {
                    pendingDispatch = null
                    dispatchPendingPayload()
                }
                pendingDispatch = dispatch
                mainHandler.postDelayed(dispatch, remainingMs)
            }
            return
        }

        pendingDispatch?.let(mainHandler::removeCallbacks)
        pendingDispatch = null
        pendingPayload = null
        lastDispatchElapsedMs = nowMs

        ensureChannel()
        val openAppIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.let {
                PendingIntent.getActivity(
                    appContext,
                    NOTIFICATION_ID,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_24)
            // Android 16 promotes the title as the prominent Live Update text.
            .setContentTitle(payload.lyric)
            .setContentText(payload.secondaryText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(payload.lyric)
                    .setSummaryText(payload.secondaryText)
            )
            .apply { payload.artwork?.let(::setLargeIcon) }
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShortCriticalText(payload.compactLyric)
            .setRequestPromotedOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 36) {
            val canPostPromoted = runCatching {
                notificationManager.canPostPromotedNotifications()
            }.getOrNull()
            val promotable = runCatching {
                notification.hasPromotableCharacteristics()
            }.getOrNull()
            Log.i(
                TAG,
                "post live lyric: canPostPromoted=$canPostPromoted " +
                    "promotable=$promotable " +
                    "request=${NotificationCompat.isRequestPromotedOngoing(notification)} " +
                    "flags=0x${notification.flags.toString(16)} channel=$CHANNEL_ID"
            )
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            lastPayload = payload
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Unable to post Live lyric notification", securityException)
        }
    }

    private fun cancelPendingDispatch() {
        pendingDispatch?.let(mainHandler::removeCallbacks)
        pendingDispatch = null
        pendingPayload = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.notification_channel_live_lyrics),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = appContext.getString(R.string.notification_channel_live_lyrics_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
