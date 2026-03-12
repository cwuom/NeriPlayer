package moe.ouom.neriplayer.core.player
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media.session.MediaButtonReceiver
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.data.FavoritesPlaylist
import moe.ouom.neriplayer.data.LocalSongSupport
import moe.ouom.neriplayer.data.displayArtist
import moe.ouom.neriplayer.data.displayName
import moe.ouom.neriplayer.data.sameIdentityAs
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import androidx.core.graphics.createBitmap
import java.io.File
import android.content.pm.ServiceInfo
import androidx.core.net.toUri

class AudioPlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "moe.ouom.neriplayer.action.PLAY"
        const val ACTION_PAUSE = "moe.ouom.neriplayer.action.PAUSE"
        const val ACTION_STOP = "moe.ouom.neriplayer.action.STOP"
        const val ACTION_NEXT = "moe.ouom.neriplayer.action.NEXT"
        const val ACTION_PREV = "moe.ouom.neriplayer.action.PREV"
        const val ACTION_SYNC = "moe.ouom.neriplayer.action.SYNC"
        const val ACTION_TOGGLE_FAV = "moe.ouom.neriplayer.action.TOGGLE_FAVORITE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "neriplayer_playback_channel"
    }

    private lateinit var becomingNoisyReceiver: BroadcastReceiver

    private lateinit var mediaSession: MediaSessionCompat

    // 灏侀潰缂撳瓨
    private var currentCoverSource: String? = null
    private var currentLargeIcon: Bitmap? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var allowServiceRestart = true
    private var hasReceivedStartCommand = false
    private var isForegroundStarted = false

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { PlayerManager.play(); updateAll() }
        override fun onPause() { PlayerManager.pause(); updateAll() }
        override fun onSkipToNext() { PlayerManager.next(); updateAll() }
        override fun onSkipToPrevious() { PlayerManager.previous(); updateAll() }
        override fun onStop() {
            PlayerManager.pause()
            updateAll()
        }
        override fun onSeekTo(pos: Long) { PlayerManager.seekTo(pos); updatePlaybackState(); updateNotification() }
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_TOGGLE_FAV) {
                if (canToggleFavoriteFromExternalSurface(PlayerManager.currentSongFlow.value)) {
                    PlayerManager.toggleCurrentFavorite()
                }
                updateAll()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 服务必须尽快进入前台，不能在这里阻塞读取 DataStore。
        PlayerManager.initialize(application as Application)

        mediaSession = MediaSessionCompat(this, "NeriPlayerSession").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }

        serviceScope.launch {
            PlayerManager.currentSongFlow.collect {
                if (it == null && !PlayerManager.hasItems()) {
                    if (!hasReceivedStartCommand) {
                        return@collect
                    }
                    stopForegroundIfStarted()
                    stopSelf()
                    return@collect
                }
                updateMetadata()
                updateNotification()
            }
        }

        serviceScope.launch {
            PlayerManager.isPlayingFlow.collect {
                updatePlaybackState()
                updateNotification()
            }
        }
        serviceScope.launch {
            PlayerManager.playbackPositionFlow.collect {
                updatePlaybackState()
            }
        }

        // 鐩戝惉瀹氭椂鍣ㄧ姸鎬佸彉鍖栧苟鏇存柊閫氱煡
        serviceScope.launch {
            PlayerManager.sleepTimerManager.timerState.collect {
                updateNotification()
            }
        }


        // 閫氱煡娓犻亾
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NeriPlayer Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        // 鎷斿嚭鑰虫満鑷姩鏆傚仠
        becomingNoisyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    NPLogger.d("NERI-APS", "Audio becoming noisy, pausing playback.")
                    PlayerManager.pause()
                    updatePlaybackState()
                    updateNotification()
                }
            }
        }
        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        updateMetadata()
        updatePlaybackState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NPLogger.d("NERI-APS", "onStartCommand action=${intent?.action}")
        allowServiceRestart = true
        hasReceivedStartCommand = true

        val action = intent?.action
        if (action == null && !PlayerManager.hasItems()) {
            allowServiceRestart = false
            stopForegroundIfStarted()
            stopSelf()
            return START_NOT_STICKY
        }

        var restoredPlayback = false
        var restoredPlaybackPositionMs: Long? = null
        if (action == null) {
            restoredPlaybackPositionMs = PlayerManager.resumeRestoredPlaybackIfNeeded()
            restoredPlayback = restoredPlaybackPositionMs != null
            if (restoredPlayback) {
                NPLogger.w("NERI-APS", "Restored playback after process restart")
                updateAll()
            }
        }

        if (action != ACTION_STOP && action != null) {
            ensureForegroundStarted()
        }

        // 处理媒体按钮
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (action) {
            ACTION_PLAY -> {
                @Suppress("DEPRECATION")
                val songList = intent.getParcelableArrayListExtra<SongItem>("playlist")
                val startIndex = intent.getIntExtra("index", 0)
                if (!songList.isNullOrEmpty()) {
                    PlayerManager.playPlaylist(songList, startIndex)
                } else if (PlayerManager.hasItems()) {
                    PlayerManager.play()
                }
                updateAll()
            }
            ACTION_PAUSE -> {
                PlayerManager.pause()
                updateAll()
            }
            ACTION_NEXT -> {
                PlayerManager.next()
                updateAll()
            }
            ACTION_PREV -> {
                PlayerManager.previous()
                updateAll()
            }
            ACTION_STOP -> {
                allowServiceRestart = false
                PlayerManager.pause()
                stopForegroundIfStarted()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SYNC -> {
                val resumedFromPendingRestore =
                    if (!PlayerManager.isPlayingFlow.value) {
                        PlayerManager.resumeRestoredPlaybackIfNeeded() != null
                    } else {
                        false
                    }
                if (!PlayerManager.hasItems()) {
                    allowServiceRestart = false
                    stopForegroundIfStarted()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (resumedFromPendingRestore) {
                    NPLogger.w("NERI-APS", "Resumed pending playback on explicit sync")
                }
                updateAll()
            }

            ACTION_TOGGLE_FAV -> {
                if (canToggleFavoriteFromExternalSurface(PlayerManager.currentSongFlow.value)) {
                    PlayerManager.toggleCurrentFavorite()
                }
                updateNotification()
            }
        }

        if (PlayerManager.hasItems()) {
            val foregroundReady = ensureForegroundStarted()
            if (!foregroundReady && action == null) {
                restoredPlaybackPositionMs?.let(PlayerManager::rearmRestoredPlayback)
                NPLogger.w(
                    "NERI-APS",
                    "Foreground start deferred after background restart; waiting for next explicit sync."
                )
                allowServiceRestart = false
                return START_NOT_STICKY
            }
        } else if (action != ACTION_STOP) {
            allowServiceRestart = false
            stopForegroundIfStarted()
            stopSelf()
            return START_NOT_STICKY
        }

        return if (allowServiceRestart) START_STICKY else START_NOT_STICKY
    }

    /**
     * 鏋勫缓鍓嶅彴鎾斁閫氱煡锛堝獟浣撴牱寮忥級
     * - 鏍规嵁 isPlaying 鍐冲畾鏄剧ず 鎾斁/鏆傚仠 鍥炬爣涓庢剰鍥?
     * - 鏀惰棌鎸夐挳浣跨敤鑷畾涔?Action
     */
    private fun buildNotification(): Notification {
        val isPlaying = PlayerManager.isPlayingFlow.value
        val song = PlayerManager.currentSongFlow.value

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent  = servicePendingIntent(ACTION_PREV, 1)
        val playIntent  = servicePendingIntent(ACTION_PLAY, 2)
        val pauseIntent = servicePendingIntent(ACTION_PAUSE, 3)
        val nextIntent  = servicePendingIntent(ACTION_NEXT, 4)
        val toggleFavIntent = servicePendingIntent(ACTION_TOGGLE_FAV, 6)
        val favoriteActionIntent = if (requiresInteractiveFavoriteConfirmation(song)) {
            contentIntent
        } else {
            toggleFavIntent
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_neri_player_round_white)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 3)
            )

        val isFav = isFavoriteSong(song)

        val favIcon = IconCompat.createWithResource(
            this,
            if (isFav) R.drawable.ic_baseline_favorite_24 else R.drawable.ic_outline_favorite_24
        )
        val favAction = NotificationCompat.Action.Builder(
            favIcon,
            if (isFav) getString(R.string.favorite_remove) else getString(R.string.favorite_add),
            favoriteActionIntent
        ).build()

        builder.addAction(R.drawable.round_skip_previous_24, getString(R.string.player_previous), prevIntent)
        builder.addAction(
            if (isPlaying) R.drawable.round_pause_24 else R.drawable.round_play_arrow_24,
            if (isPlaying) getString(R.string.player_pause) else getString(R.string.player_play),
            if (isPlaying) pauseIntent else playIntent
        )
        builder.addAction(favAction)
        builder.addAction(R.drawable.round_skip_next_24, getString(R.string.player_next), nextIntent)

        // 璁剧疆鏍囬鍜屽壇鏍囬
        builder.setContentTitle(song?.displayName() ?: "NeriPlayer")

        // 濡傛灉瀹氭椂鍣ㄦ縺娲伙紝鍦ㄥ壇鏍囬涓樉绀哄墿浣欐椂闂?
        val timerState = PlayerManager.sleepTimerManager.timerState.value
        val contentText = if (timerState.isActive) {
            val timerInfo = when (timerState.mode) {
                SleepTimerMode.COUNTDOWN -> {
                    val remaining = PlayerManager.sleepTimerManager.formatRemainingTime()
                    getString(R.string.notification_timer_remaining, remaining)
                }
                SleepTimerMode.FINISH_CURRENT -> getString(R.string.notification_stop_after_current)
                SleepTimerMode.FINISH_PLAYLIST -> getString(R.string.notification_stop_after_playlist)
            }
            song?.displayArtist()
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it | $timerInfo" }
                ?: timerInfo
        } else {
            song?.displayArtist() ?: ""
        }
        builder.setContentText(contentText)

        currentLargeIcon?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    /**
     * 鑱氬悎鏇存柊
     */
    private fun isFavoriteSong(song: SongItem?): Boolean {
        if (song == null) return false
        val favorites = PlayerManager.playlistsFlow.value
            .firstOrNull { FavoritesPlaylist.isSystemPlaylist(it, this) }
            ?: return false
        return favorites.songs.any { it.sameIdentityAs(song) }
    }

    private fun requiresInteractiveFavoriteConfirmation(song: SongItem?): Boolean {
        if (song == null) return false
        return !isFavoriteSong(song) && LocalSongSupport.isLocalSong(song, this)
    }

    private fun canToggleFavoriteFromExternalSurface(song: SongItem?): Boolean {
        return !requiresInteractiveFavoriteConfirmation(song)
    }

    private fun updateAll() {
        updateMetadata()
        updatePlaybackState()
        updateNotification()
    }

    /** 鏋勫缓鎸囧悜鏈?Service 鐨?PendingIntent */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AudioPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        if (!isForegroundStarted) {
            return
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateMetadata() {
        val song = PlayerManager.currentSongFlow.value
        val duration = song?.durationMs ?: 0L
        val coverSource = song.effectiveCoverSource()
        val coverUri = normalizeArtworkUri(coverSource)

        // 灏侀潰 URL 鍙樻洿鍒欏紓姝ュ姞杞?
        if (coverSource != currentCoverSource) {
            currentCoverSource = coverSource
            currentLargeIcon = null
            requestLargeIconAsync(coverSource)
        }

        val displayTitle = song?.displayName() ?: "NeriPlayer"
        val displayArtist = song?.displayArtist().orEmpty()

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentLargeIcon)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, currentLargeIcon)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, currentLargeIcon)

        coverUri?.let {
            metadataBuilder
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it)
                .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun updatePlaybackState() {
        val isPlaying = PlayerManager.isPlayingFlow.value
        val pos = PlayerManager.playbackPositionFlow.value

        val song = PlayerManager.currentSongFlow.value
        val isFav = isFavoriteSong(song)

        val favIconRes = if (isFav) R.drawable.ic_baseline_favorite_24
        else R.drawable.ic_outline_favorite_24
        val favText = if (isFav) getString(R.string.favorite_remove) else getString(R.string.favorite_add)

        val favCustom = PlaybackStateCompat.CustomAction.Builder(
            ACTION_TOGGLE_FAV, favText, favIconRes
        ).build()

        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                pos,
                if (isPlaying) 1.0f else 0.0f
            )

        if (canToggleFavoriteFromExternalSurface(song)) {
            stateBuilder.addCustomAction(favCustom)
        }

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    /**
     * 浣跨敤 Coil 寮傛鍔犺浇閫氱煡澶у浘鏍囧皝闈?
     * 浠呭綋鍥炶皟鏃?URL 浠嶆槸褰撳墠鏇茬洰鐨勫皝闈㈡椂鎵嶅簲鐢紝閬垮厤绔炴€佸鑷寸殑灏侀潰閿欎綅
     */
    private fun requestLargeIconAsync(url: String?) {
        if (url.isNullOrBlank()) {
            // 娓呯┖灏侀潰 UI
            currentLargeIcon = null
            updateNotification()
            return
        }
        val appCtx = applicationContext
        serviceScope.launch(Dispatchers.IO) {
            try {
                val loader = coil.Coil.imageLoader(appCtx)
                val request = ImageRequest.Builder(appCtx)
                    .data(url)
                    .allowHardware(false)
                    .size(512)
                    .build()
                val result = loader.execute(request)
                val drawable = result.drawable ?: return@launch
                val bmp = drawable.toBitmap()
                withContext(Dispatchers.Main) {
                    if (url == currentCoverSource) {
                        currentLargeIcon = bmp
                        updateMetadata()
                        updateNotification()
                    }
                }
            } catch (e: Exception) {
                NPLogger.d("NERI-APS", "Cover load failed: ${e.message}")
            }
        }
    }

    private fun SongItem?.effectiveCoverSource(): String? {
        val song = this ?: return null
        return song.customCoverUrl?.takeIf { it.isNotBlank() }
            ?: song.coverUrl?.takeIf { it.isNotBlank() }
    }

    private fun normalizeArtworkUri(source: String?): String? {
        val raw = source?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { raw.toUri() }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        return when {
            scheme.isNullOrBlank() -> filePathToUriString(raw)
            scheme == "file" -> parsed?.path?.let(::filePathToUriString) ?: raw
            else -> raw
        }
    }

    private fun filePathToUriString(path: String): String {
        val file = File(path)
        return Uri.fromFile(file).toString()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 前台服务已在运行时，不需要额外从最近任务移除回调里重新拉起服务。
        // 这里主动 startService 更容易在后台启动限制下失败或制造重复生命周期。
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(becomingNoisyReceiver)
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        if (!allowServiceRestart || !PlayerManager.hasItems()) {
            PlayerManager.release()
        }
        super.onDestroy()
    }


    private fun ensureForegroundStarted(): Boolean {
        if (isForegroundStarted) {
            updateNotification()
            return true
        }
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
            true
        } catch (e: SecurityException) {
            NPLogger.e("NERI-APS", "Failed to start foreground service", e)
            false
        } catch (e: RuntimeException) {
            if (isForegroundStartNotAllowed(e)) {
                NPLogger.w("NERI-APS", "startForeground not allowed right now: ${e.message}")
                false
            } else {
                throw e
            }
        }
    }

    private fun isForegroundStartNotAllowed(error: RuntimeException): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun stopForegroundIfStarted() {
        if (!isForegroundStarted) {
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
    }

    private fun NotificationPaddedIcon(
        @DrawableRes resId: Int,
        boxDp: Int = 24,
        glyphDp: Int = 18
    ): IconCompat {
        val d = AppCompatResources.getDrawable(this, resId)!!.mutate()
        DrawableCompat.setTintList(d, null)

        fun dp2px(dp: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()

        val boxPx = dp2px(boxDp)
        val glyphPx = dp2px(glyphDp)
        val left = (boxPx - glyphPx) / 2
        val top  = (boxPx - glyphPx) / 2

        val bmp = createBitmap(boxPx, boxPx)
        val canvas = Canvas(bmp)
        d.setBounds(left, top, left + glyphPx, top + glyphPx)
        d.draw(canvas)

        return IconCompat.createWithBitmap(bmp)
    }
}
