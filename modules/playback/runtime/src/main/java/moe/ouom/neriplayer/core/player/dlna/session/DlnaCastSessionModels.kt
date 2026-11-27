package moe.ouom.neriplayer.core.player.dlna.session

import moe.ouom.neriplayer.data.model.SongItem
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
    val streamUrl: String,
    val streamPath: String,
    val coverPath: String?,
    val didl: String,
    val sentToRenderer: Boolean,
    val rejectedByRenderer: Boolean = false
)
