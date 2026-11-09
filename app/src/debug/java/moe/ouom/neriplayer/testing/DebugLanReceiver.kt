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
            "local-fixture" -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    try {
                        val song = withContext(Dispatchers.IO) {
                            val file = File(context.filesDir, "lan-test/fixture.mp3")
                            val contentSource = intent.getBooleanExtra("contentSource", false)
                            val uri = if (contentSource) {
                                val exported = File(context.cacheDir, "shared_media_exports/lan-fixture.mp3")
                                exported.parentFile?.mkdirs(); file.copyTo(exported, overwrite = true)
                                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported)
                            } else android.net.Uri.fromFile(file)
                            val metadata = moe.ouom.neriplayer.data.local.media.LocalMediaSupport.inspectMetadataOnly(context, uri)
                            moe.ouom.neriplayer.data.model.SongItem(id = 987654321L, name = metadata.title,
                                artist = metadata.artist, album = metadata.album, albumId = 0,
                                durationMs = metadata.durationMs, coverUrl = metadata.coverUri,
                                mediaUri = uri.toString(), localFilePath = if (contentSource) null else file.absolutePath,
                                customName = if (intent.getBooleanExtra("customMetadata", false)) "Custom LAN title" else null,
                                customArtist = if (intent.getBooleanExtra("customMetadata", false)) "Custom LAN artist" else null,
                                channelId = "local", audioId = "987654321")
                        }
                        PlayerManager.playPlaylist(listOf(song), 0)
                    } finally { pending.finish() }
                }
                return
            }
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
            put("title", PlayerManager.currentSongFlow.value?.name)
            put("artist", PlayerManager.currentSongFlow.value?.artist)
            put("hasCover", !PlayerManager.currentSongFlow.value?.coverUrl.isNullOrBlank())
            put("sharedLyricLines", moe.ouom.neriplayer.core.player.lan.LanSharedPresentationStore.forSong(PlayerManager.currentSongFlow.value)?.lyrics?.size ?: 0)
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
