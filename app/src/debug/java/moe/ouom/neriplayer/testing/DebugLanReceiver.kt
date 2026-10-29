package moe.ouom.neriplayer.testing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import java.io.File

/** Shell-only diagnostics for the production LAN implementation, excluded from release. */
class DebugLanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = AppContainer.lanListenTogether
        when (intent.getStringExtra("operation")) {
            "restore" -> PlayerManager.play()
            "host" -> manager.host("Debug host")
            "join" -> manager.join(intent.getStringExtra("address") ?: return, intent.getStringExtra("room"), "Debug member")
            "leave" -> manager.leave()
            "play" -> manager.requestPlayback("PLAY_PAUSE", manager.state.value.room?.playback?.track, 0.0)
            "pause" -> manager.requestPlayback("PAUSE", null, 0.0)
            "sync" -> manager.sync()
            "seek" -> manager.requestPlayback("SEEK", manager.state.value.room?.playback?.track, intent.getDoubleExtra("position", 0.0))
            "reset-nudge" -> manager.nudge(-manager.state.value.nudgeMs)
            "permissions" -> manager.setPermissions(moe.ouom.neriplayer.data.model.lan.LanPermissions(
                next = intent.getBooleanExtra("next", false), playPause = intent.getBooleanExtra("playPause", false),
                seek = intent.getBooleanExtra("seek", false)))
            "status" -> Unit
            "diagnostics" -> File(context.filesDir, "lan-audio-diagnostics.json").writeText(manager.audioDiagnostics())
            else -> { resultCode = Activity.RESULT_CANCELED; return }
        }
        val state = manager.state.value
        val result = buildJsonObject {
            put("phase", state.phase); put("synced", state.synced); put("rttMs", state.rttMs)
            put("hosting", state.hosting); put("room", state.room?.id); put("port", state.port)
            put("members", state.room?.members?.size ?: 0); put("error", state.error); put("preparing", state.preparing)
            put("nudgeMs", state.nudgeMs); put("playing", PlayerManager.isPlayingFlow.value)
            put("positionMs", PlayerManager.playbackPositionFlow.value)
            put("metadataDurationMs", PlayerManager.currentSongFlow.value?.durationMs)
            put("publishedDurationMs", PlayerManager.playbackDurationFlow.value)
            put("roomVolume", state.room?.volume)
            put("systemVolume", (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).let { it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) })
            put("roomChannel", state.room?.playback?.track?.channelId)
            put("songChannel", PlayerManager.currentSongFlow.value?.channelId)
            put("audioSource", PlayerManager.currentPlaybackAudioInfoFlow.value?.source?.name)
            put("qualityKey", PlayerManager.currentPlaybackAudioInfoFlow.value?.qualityKey)
            put("qualityLabel", PlayerManager.currentPlaybackAudioInfoFlow.value?.qualityLabel)
            put("codec", PlayerManager.currentPlaybackAudioInfoFlow.value?.codecLabel)
            put("sampleRateHz", PlayerManager.currentPlaybackAudioInfoFlow.value?.sampleRateHz)
            put("bitrateKbps", PlayerManager.currentPlaybackAudioInfoFlow.value?.bitrateKbps)
            put("permissions", buildJsonObject {
                put("next", state.room?.permissions?.next); put("playPause", state.room?.permissions?.playPause)
                put("seek", state.room?.permissions?.seek)
            })
        }.toString()
        File(context.filesDir, "lan-debug-status.json").writeText(result)
        resultCode = Activity.RESULT_OK; resultData = result
    }
}
