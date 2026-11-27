package moe.ouom.neriplayer.core.player.dlna.session

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaAudioQualityReport
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaPlaybackSnapshot

internal data class LocalHandoffSnapshot(
    val positionMs: Long,
    val wasPlaying: Boolean
)

internal data class PendingCastRequest(
    val song: SongItem,
    val startPositionMs: Long,
    val autoPlay: Boolean,
    val trackGen: Long
)

internal data class PendingVolumeCommand(
    val deviceUdn: String,
    val connectionGen: Long,
    val volume: Int
)

internal data class QueuedNextTrack(
    val connectionGen: Long,
    val forGenerationId: Long,
    val queueRevision: Long,
    val song: SongItem,
    val snapshot: DlnaPlaybackSnapshot,
    val qualityReport: DlnaAudioQualityReport,
    // 预取时不发布音频信息，晋升为当前曲目时再发布，否则音质弹窗仍按上一首的来源写设置
    val audioInfo: PlaybackAudioInfo?,
    val streamUrl: String,
    val streamPath: String,
    val coverPath: String?,
    val didl: String,
    val sentToRenderer: Boolean,
    val rejectedByRenderer: Boolean = false
)

internal sealed interface DlnaUrlResolution {
    data class Success(val result: SongUrlResult.Success) : DlnaUrlResolution
    data object RequiresLogin : DlnaUrlResolution
    data class Failure(val customMessage: String? = null) : DlnaUrlResolution
}

// Hook callbacks invoked on main thread to keep PlayerManager in sync
internal interface PlayerSyncHost {
    fun onSwitchToDlnaPauseLocal(willAutoPlay: Boolean = true)
    fun onSwitchBackToLocal(resumePositionMs: Long, wasPlaying: Boolean)
    fun onDlnaPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long)
    fun onDlnaTrackEnded(generationId: Long)
    fun onDlnaGaplessTransitionedToNext(expectedSong: SongItem, audioInfo: PlaybackAudioInfo?)
    fun peekNextAutoTrack(): SongItem?
    fun onDlnaCastError(message: String)
    suspend fun resolveSongUrlForDlna(song: SongItem, forPrequeue: Boolean = false): DlnaUrlResolution
    fun currentSong(): SongItem?
    fun currentPositionMs(): Long
    fun isLocalPlaying(): Boolean
    // 一起听房间里由独立的 LAN 引擎发声并接管播放命令，此时投送会与房间音频同时出声
    fun castBlockedReason(): String? = null
}
