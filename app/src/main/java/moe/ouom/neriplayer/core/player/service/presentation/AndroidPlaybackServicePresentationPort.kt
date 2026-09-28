package moe.ouom.neriplayer.core.player.service.presentation

import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.service.notification.ServiceWidgetLabels
import moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState
import moe.ouom.neriplayer.core.player.service.notification.favoriteActionIcon
import moe.ouom.neriplayer.core.player.service.notification.favoriteActionTitle
import moe.ouom.neriplayer.core.player.service.notification.floatingLyricsActionIcon
import moe.ouom.neriplayer.core.player.service.notification.floatingLyricsActionTitle
import moe.ouom.neriplayer.core.player.service.notification.playbackControlActionChoice
import moe.ouom.neriplayer.core.player.service.notification.serviceNotificationTitle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.util.media.IsLandHelp
import moe.ouom.neriplayer.util.media.isShareablePublicHttpUrl
import moe.ouom.neriplayer.widget.PlaybackWidgetState
import moe.ouom.neriplayer.widget.PlaybackWidgetUpdater

internal data class PlaybackServiceNotificationRenderInputs(
    val song: SongItem?,
    val text: String,
    val audioRouteMuted: Boolean,
    val playbackControlPlaying: Boolean,
    val favorite: Boolean,
    val interactiveFavorite: Boolean,
    val floatingLyricsEnabled: Boolean,
    val lyricState: StatusBarLyricNotificationState,
    val artwork: Bitmap?,
    val shareUrl: String?,
)

internal fun resolveXiaomiIslandShare(song: SongItem?, shareUrl: String?): Pair<SongItem, String>? {
    val currentSong = song ?: return null
    val publicUrl = shareUrl?.takeIf(::isShareablePublicHttpUrl) ?: return null
    return currentSong to publicUrl
}

internal fun mediaButtonKeyEvent(intent: Intent?): KeyEvent? {
    val mediaButtonIntent = intent ?: return null
    if (mediaButtonIntent.action != Intent.ACTION_MEDIA_BUTTON) return null
    return IntentCompat.getParcelableExtra(
        mediaButtonIntent,
        Intent.EXTRA_KEY_EVENT,
        KeyEvent::class.java,
    )
}

internal interface PlaybackServicePresentationPort {
    fun initializeSession(callback: MediaSession.Callback)
    fun sessionOrNull(): MediaSession?
    fun audioAttributes(): AudioAttributes
    fun releaseSessionAfterForegroundFailure(reason: String)
    fun releaseSessionForDestroy()
    fun ensureNotificationChannel()
    fun buildBootstrapNotification(): Notification
    fun buildMinimalForegroundNotification(): Notification
    fun buildNotification(inputs: PlaybackServiceNotificationRenderInputs): Notification
    fun publishNotification(inputs: PlaybackServiceNotificationRenderInputs)
    fun hasInstalledWidgets(): Boolean
    fun publishWidget(state: PlaybackWidgetState, artwork: Bitmap?)
    fun publishWidgetProgress(state: PlaybackWidgetState)
    fun setMetadata(metadata: MediaMetadata)
    fun setPlaybackState(state: PlaybackState)
    fun dispatchMediaButtonIntent(intent: Intent?)
    fun elapsedRealtime(): Long
    fun localizedString(resId: Int, arg: String?): String
    fun widgetLabels(): ServiceWidgetLabels
}

internal class AndroidPlaybackServicePresentationPort(
    private val context: Context,
) : PlaybackServicePresentationPort {
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var mediaSession: MediaSession? = null

    override fun initializeSession(callback: MediaSession.Callback) {
        mediaSession = MediaSession(context, "NeriPlayerSession").apply {
            setCallback(callback)
            setPlaybackToLocal(audioAttributes)
            isActive = true
        }
    }

    override fun sessionOrNull(): MediaSession? = mediaSession

    override fun audioAttributes(): AudioAttributes = audioAttributes

    override fun releaseSessionAfterForegroundFailure(reason: String) {
        val session = mediaSession ?: return
        releaseSessionSafely(session, "media session release failed after FGS failure reason=$reason")
    }

    override fun releaseSessionForDestroy() {
        val session = mediaSession ?: return
        releaseSessionSafely(session, "media session release failed")
    }

    private fun releaseSessionSafely(session: MediaSession, failureMessage: String) {
        runCatching { releaseSession(session) }
            .onFailure { NPLogger.w("NERI-APS", failureMessage, it) }
    }

    override fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            AudioPlayerService.CHANNEL_ID,
            "NeriPlayer Playback",
            NotificationManager.IMPORTANCE_LOW,
        )
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    override fun buildBootstrapNotification(): Notification {
        val builder = Notification.Builder(context, AudioPlayerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.player_notification_preparing))
            .setContentIntent(mainActivityPendingIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .applyForegroundServiceBehavior()
        mediaSession?.let { session ->
            builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
        }
        return builder.build()
    }

    override fun buildMinimalForegroundNotification(): Notification =
        NotificationCompat.Builder(context, AudioPlayerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(context.getString(R.string.app_name))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    override fun publishNotification(inputs: PlaybackServiceNotificationRenderInputs) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(AudioPlayerService.NOTIFICATION_ID, buildNotification(inputs))
    }

    override fun hasInstalledWidgets(): Boolean = PlaybackWidgetUpdater.hasInstalledWidgets(context)

    override fun publishWidget(state: PlaybackWidgetState, artwork: Bitmap?) {
        PlaybackWidgetUpdater.updateFromPlaybackService(context, state, artwork)
    }

    override fun publishWidgetProgress(state: PlaybackWidgetState) {
        PlaybackWidgetUpdater.updatePlaybackProgressFromPlaybackService(context, state)
    }

    override fun setMetadata(metadata: MediaMetadata) {
        requireSession().setMetadata(metadata)
    }

    override fun setPlaybackState(state: PlaybackState) {
        requireSession().setPlaybackState(state)
    }

    override fun dispatchMediaButtonIntent(intent: Intent?) {
        val keyEvent = mediaButtonKeyEvent(intent) ?: return
        requireSession().controller.dispatchMediaButtonEvent(keyEvent)
    }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun localizedString(resId: Int, arg: String?): String =
        if (arg == null) context.getString(resId) else context.getString(resId, arg)

    override fun widgetLabels(): ServiceWidgetLabels = ServiceWidgetLabels(
        appName = context.getString(R.string.app_name),
        idleSubtitle = context.getString(R.string.widget_playback_idle_subtitle),
        buffering = context.getString(R.string.widget_playback_buffering),
        playing = context.getString(R.string.widget_playback_playing),
        paused = context.getString(R.string.widget_playback_paused),
        ready = context.getString(R.string.widget_playback_ready),
    )

    private fun releaseSession(session: MediaSession) {
        session.isActive = false
        session.release()
    }

    private fun requireSession(): MediaSession = checkNotNull(mediaSession)

    override fun buildNotification(inputs: PlaybackServiceNotificationRenderInputs): Notification {
        val contentIntent = mainActivityPendingIntent()
        val builder = mediaNotificationBuilder(contentIntent)
        addMediaNotificationActions(builder, inputs, contentIntent)
        applyMediaNotificationText(builder, inputs)
        applyMediaNotificationArtwork(builder, inputs.artwork)
        return finishNotification(builder, inputs)
    }

    private fun applyMediaNotificationText(
        builder: Notification.Builder,
        inputs: PlaybackServiceNotificationRenderInputs,
    ) {
        builder.setContentTitle(serviceNotificationTitle(inputs.song))
        builder.setContentText(inputs.text)
        inputs.lyricState.line?.let(builder::setTicker)
    }

    private fun applyMediaNotificationArtwork(builder: Notification.Builder, artwork: Bitmap?) {
        artwork?.let(builder::setLargeIcon)
    }

    private fun mediaNotificationBuilder(contentIntent: PendingIntent): Notification.Builder =
        Notification.Builder(context, AudioPlayerService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(requireSession().sessionToken)
                    .setShowActionsInCompactView(0, 1, 3),
            )
            .applyForegroundServiceBehavior()

    private fun addMediaNotificationActions(
        builder: Notification.Builder,
        inputs: PlaybackServiceNotificationRenderInputs,
        contentIntent: PendingIntent,
    ) {
        builder.addAction(mediaNotificationAction(
            R.drawable.round_skip_previous_24,
            context.getString(R.string.player_previous),
            servicePendingIntent(AudioPlayerService.ACTION_PREV, 1),
        ))
        builder.addAction(playbackControlNotificationAction(inputs))
        builder.addAction(favoriteNotificationAction(inputs, contentIntent))
        builder.addAction(mediaNotificationAction(
            R.drawable.round_skip_next_24,
            context.getString(R.string.player_next),
            servicePendingIntent(AudioPlayerService.ACTION_NEXT, 4),
        ))
        builder.addAction(floatingLyricsNotificationAction(inputs.floatingLyricsEnabled))
    }

    private fun playbackControlNotificationAction(inputs: PlaybackServiceNotificationRenderInputs): Notification.Action {
        val choice = playbackControlActionChoice(inputs.audioRouteMuted, inputs.playbackControlPlaying)
        val intents = arrayOf(
            servicePendingIntent(AudioPlayerService.ACTION_PLAY, 2),
            servicePendingIntent(AudioPlayerService.ACTION_PAUSE, 3),
            servicePendingIntent(AudioPlayerService.ACTION_RESTORE_VOLUME, 8),
        )
        return mediaNotificationAction(choice.iconRes, context.getString(choice.titleRes), intents[choice.intentIndex])
    }

    private fun favoriteNotificationAction(
        inputs: PlaybackServiceNotificationRenderInputs,
        contentIntent: PendingIntent,
    ): Notification.Action {
        val intent = if (inputs.interactiveFavorite) contentIntent
        else servicePendingIntent(AudioPlayerService.ACTION_TOGGLE_FAV, 6)
        return mediaNotificationAction(
            favoriteActionIcon(inputs.favorite),
            context.getString(favoriteActionTitle(inputs.favorite)),
            intent,
        )
    }

    private fun floatingLyricsNotificationAction(enabled: Boolean): Notification.Action =
        mediaNotificationAction(
            floatingLyricsActionIcon(enabled),
            context.getString(floatingLyricsActionTitle(enabled)),
            servicePendingIntent(AudioPlayerService.ACTION_TOGGLE_FLOATING_LYRICS, 7),
        )

    private fun finishNotification(
        builder: Notification.Builder,
        inputs: PlaybackServiceNotificationRenderInputs,
    ): Notification {
        val notification = builder.build()
        notification.attachXiaomiMusicIslandShareExtras(inputs)
        applyMediaNotificationLyricFlags(notification, inputs.lyricState)
        return notification
    }

    private fun Notification.attachXiaomiMusicIslandShareExtras(inputs: PlaybackServiceNotificationRenderInputs) {
        val (song, shareUrl) = resolveXiaomiIslandShare(inputs.song, inputs.shareUrl) ?: return
        attachXiaomiMusicIslandShareExtras(song, shareUrl)
    }

    private fun Notification.attachXiaomiMusicIslandShareExtras(song: SongItem, shareUrl: String) {
        runCatching {
            val icon = Bundle().apply {
                putParcelable("miui_media_album_icon", Icon.createWithResource(context, R.drawable.ic_notification_small))
            }
            val islandBundle = IsLandHelp.isLandMusicShare(
                addpic = icon,
                title = song.displayName(),
                content = song.displayArtist(),
                shareContent = shareUrl,
            )
            extras.putAll(islandBundle)
        }.onFailure { NPLogger.w("NERI-APS", "Xiaomi music island share extras failed", it) }
    }

    private fun applyMediaNotificationLyricFlags(
        notification: Notification,
        lyricState: StatusBarLyricNotificationState,
    ) {
        if (!lyricState.hasTicker) return
        val alwaysShowTicker = 0x01000000
        val onlyUpdateTicker = 0x02000000
        notification.flags = notification.flags.or(alwaysShowTicker).or(onlyUpdateTicker)
        notification.extras.putInt("ticker_icon", R.drawable.ic_statusbar_lyric)
        notification.extras.putBoolean("ticker_icon_switch", false)
    }

    private fun mediaNotificationAction(
        @DrawableRes iconRes: Int,
        title: CharSequence,
        pendingIntent: PendingIntent,
    ): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(context, iconRes),
        title,
        pendingIntent,
    ).build()

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context,
        requestCode,
        Intent(context, AudioPlayerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun Notification.Builder.applyForegroundServiceBehavior(): Notification.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return this
    }
}
