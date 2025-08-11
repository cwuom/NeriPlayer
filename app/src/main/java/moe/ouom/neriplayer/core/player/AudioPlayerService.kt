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
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.ui.viewmodel.SongItem
import moe.ouom.neriplayer.util.NPLogger

class AudioPlayerService : Service() {
    companion object {
        const val ACTION_PLAY = "moe.ouom.neriplayer.action.PLAY"
        const val ACTION_PAUSE = "moe.ouom.neriplayer.action.PAUSE"
        const val ACTION_STOP = "moe.ouom.neriplayer.action.STOP"
        const val ACTION_NEXT = "moe.ouom.neriplayer.action.NEXT"
        const val ACTION_PREV = "moe.ouom.neriplayer.action.PREV"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "neriplayer_playback_channel"
    }

    private lateinit var becomingNoisyReceiver: BroadcastReceiver

    override fun onCreate() {
        super.onCreate()
        PlayerManager.initialize(application as Application)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "NeriPlayer Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        becomingNoisyReceiver = BecomingNoisyReceiver()
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, intentFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NPLogger.d("NERI-APS", "onStartCommand action=${intent?.action}")

        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_PLAY -> {
                val songList = intent.getParcelableArrayListExtra<SongItem>("playlist")
                val startIndex = intent.getIntExtra("index", 0)
                NPLogger.d("NERI-APS", "PLAY size=${songList?.size} index=$startIndex")

                if (!songList.isNullOrEmpty()) {
                    PlayerManager.playPlaylist(songList, startIndex)
//                    PlayerManager.play()
                } else {
                    // 如果之前已经有队列，直接播
                    if (PlayerManager.hasItems()) {
                        PlayerManager.play()
                    } else {
                        Log.w("NERI-APS", "No playlist provided and player has no items.")
                    }
                }
                updateNotification()
            }
            ACTION_PAUSE -> {
                PlayerManager.pause(); updateNotification()
            }
            ACTION_NEXT -> {
                PlayerManager.next(); updateNotification()
            }
            ACTION_PREV -> {
                PlayerManager.previous(); updateNotification()
            }
            ACTION_STOP -> {
                PlayerManager.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val activityIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val songName = PlayerManager.currentSongFlow.value?.name ?: "NeriPlayer"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NeriPlayer")
            .setContentText(songName)
            .setSmallIcon(R.drawable.ic_neri_player_round)
            .setContentIntent(activityIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(becomingNoisyReceiver)
        PlayerManager.release()
        super.onDestroy()
    }

    private inner class BecomingNoisyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                NPLogger.d("NERI-APS", "Audio becoming noisy, pausing playback.")
                PlayerManager.pause()
                updateNotification()
            }
        }
    }
}