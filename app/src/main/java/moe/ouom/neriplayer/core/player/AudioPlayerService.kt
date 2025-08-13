package moe.ouom.neriplayer.core.player

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player/AudioPlayerService
 * Created: 2025/8/11
 */


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
import android.os.Bundle
import android.os.IBinder
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
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.ui.viewmodel.SongItem
import moe.ouom.neriplayer.util.NPLogger
import androidx.core.graphics.createBitmap

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

    private var progressJob: Job? = null
    private lateinit var becomingNoisyReceiver: BroadcastReceiver

    private lateinit var mediaSession: MediaSessionCompat

    // 封面缓存
    private var currentCoverUrl: String? = null
    private var currentLargeIcon: Bitmap? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() { PlayerManager.play(); updatePlaybackState(); updateNotification() }
        override fun onPause() { PlayerManager.pause(); updatePlaybackState(); updateNotification() }
        override fun onSkipToNext() { PlayerManager.next(); updateMetadata(); updatePlaybackState(); updateNotification() }
        override fun onSkipToPrevious() { PlayerManager.previous(); updateMetadata(); updatePlaybackState(); updateNotification() }
        override fun onStop() { PlayerManager.pause(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        override fun onSeekTo(pos: Long) { PlayerManager.seekTo(pos); updatePlaybackState(); updateNotification() }
        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_TOGGLE_FAV) {
                PlayerManager.toggleCurrentFavorite()
                updatePlaybackState()
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlayerManager.initialize(application as Application)

        mediaSession = MediaSessionCompat(this, "NeriPlayerSession").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }

        serviceScope.launch {
            PlayerManager.currentSongFlow.collect {
                updateMetadata()
                updatePlaybackState()
                updateNotification()
            }
        }

        // 通知渠道
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NeriPlayer Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        // 拔出耳机自动暂停
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

        startForeground(NOTIFICATION_ID, buildNotification())

        // 处理媒体按钮
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_PLAY -> {
                val songList = intent.getParcelableArrayListExtra<SongItem>("playlist")
                val startIndex = intent.getIntExtra("index", 0)
                if (!songList.isNullOrEmpty()) {
                    PlayerManager.playPlaylist(songList, startIndex)
                } else if (PlayerManager.hasItems()) {
                    PlayerManager.play()
                }
                updateMetadata()
                updatePlaybackState()
            }
            ACTION_PAUSE -> PlayerManager.pause()
            ACTION_NEXT -> {
                PlayerManager.next()
                updateMetadata()
                updatePlaybackState()
                updateNotification()
            }
            ACTION_PREV -> {
                PlayerManager.previous()
                updateMetadata()
                updatePlaybackState()
                updateNotification()
            }
            ACTION_STOP -> {
                PlayerManager.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SYNC -> {
                if (!PlayerManager.hasItems()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                updateMetadata()
                updatePlaybackState()
                updateNotification()
            }

            ACTION_TOGGLE_FAV -> {
                PlayerManager.toggleCurrentFavorite()
                updateNotification()
            }
        }

//        val notif = buildNotification()
//        startForeground(NOTIFICATION_ID, notif)

        // 进度轮询
        if (progressJob == null) {
            progressJob = serviceScope.launch {
                while (isActive) {
                    updatePlaybackState()
                    delay(1000)
                }
            }
        }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val isPlaying = PlayerManager.isPlayingFlow.value
        val song = PlayerManager.currentSongFlow.value

        val playlists = PlayerManager.playlistsFlow.value
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent  = PendingIntent.getService(this, 1, Intent(this, AudioPlayerService::class.java).setAction(ACTION_PREV),  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playIntent  = PendingIntent.getService(this, 2, Intent(this, AudioPlayerService::class.java).setAction(ACTION_PLAY),  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseIntent = PendingIntent.getService(this, 3, Intent(this, AudioPlayerService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nextIntent  = PendingIntent.getService(this, 4, Intent(this, AudioPlayerService::class.java).setAction(ACTION_NEXT),  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val toggleFavIntent = PendingIntent.getService(
            this, 6,
            Intent(this, AudioPlayerService::class.java).setAction(ACTION_TOGGLE_FAV),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_neri_player_round)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 3)
            )

        builder.addAction(android.R.drawable.ic_media_previous, "上一首", prevIntent)

        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "播放", playIntent)
        }

        val isFav = PlayerManager.playlistsFlow.value
            .firstOrNull { it.name == "我喜欢的音乐" }
            ?.songs?.any { it.id == song?.id } == true

        val favIcon = IconCompat.createWithResource(
            this,
            if (isFav) R.drawable.ic_baseline_favorite_24 else R.drawable.ic_outline_favorite_24
        )

        val favAction = NotificationCompat.Action.Builder(
            favIcon,
            if (isFav) "取消收藏" else "收藏",
            toggleFavIntent
        ).build()

        builder.addAction(android.R.drawable.ic_media_previous, "上一首", prevIntent)
        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "播放", playIntent)
        }
        builder.addAction(favAction)
        builder.addAction(android.R.drawable.ic_media_next, "下一首", nextIntent)

        // 标题/副标题/封面
        builder.setContentTitle(song?.name ?: "NeriPlayer")
        builder.setContentText(song?.artist ?: "")
        currentLargeIcon?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateMetadata() {
        val song = PlayerManager.currentSongFlow.value
        val duration = song?.durationMs ?: 0L

        // 封面 URL 变更则异步加载
        if (song?.coverUrl != currentCoverUrl) {
            currentCoverUrl = song?.coverUrl
            currentLargeIcon = null
            requestLargeIconAsync(currentCoverUrl)
        }

        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song?.name ?: "NeriPlayer")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song?.artist ?: "")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentLargeIcon)
            .build()

        mediaSession.setMetadata(metadata)
    }

    private fun updatePlaybackState() {
        val isPlaying = PlayerManager.isPlayingFlow.value
        val pos = PlayerManager.playbackPositionFlow.value

        val song = PlayerManager.currentSongFlow.value
        val isFav = PlayerManager.playlistsFlow.value
            .firstOrNull { it.name == "我喜欢的音乐" }
            ?.songs?.any { it.id == song?.id } == true

        val favIconRes = if (isFav) R.drawable.ic_baseline_favorite_24
        else R.drawable.ic_outline_favorite_24
        val favText = if (isFav) "取消收藏" else "收藏"

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
            .addCustomAction(favCustom)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                pos,
                if (isPlaying) 1.0f else 0.0f
            )

        mediaSession.setPlaybackState(stateBuilder.build())
    }

    /**
     * 使用 Coil 异步加载通知大图标封面
     */
    private fun requestLargeIconAsync(url: String?) {
        if (url.isNullOrBlank()) {
            // 清空封面 UI
            currentLargeIcon = null
            updateNotification()
            return
        }
        val appCtx = applicationContext
        serviceScope.launch(Dispatchers.IO) {
            try {
                val loader = ImageLoader(appCtx)
                val request = ImageRequest.Builder(appCtx)
                    .data(url)
                    .allowHardware(false)
                    .size(512)
                    .build()
                val result = loader.execute(request)
                val drawable = result.drawable ?: return@launch
                val bmp = drawable.toBitmap()
                withContext(Dispatchers.Main) {
                    if (url == currentCoverUrl) {
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(becomingNoisyReceiver)
        progressJob?.cancel()
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        PlayerManager.release()
        super.onDestroy()
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
