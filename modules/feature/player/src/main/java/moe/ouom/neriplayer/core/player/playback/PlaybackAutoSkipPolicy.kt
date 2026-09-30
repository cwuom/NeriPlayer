package moe.ouom.neriplayer.core.player.playback

import moe.ouom.neriplayer.core.player.policy.skip.BiliSkipSegmentSource
import moe.ouom.neriplayer.data.model.SongItem

internal data class PlaybackAutoSkipDecision(
    val positionMs: Long,
    val source: BiliSkipSegmentSource,
    val logTag: String,
    val logAction: String,
    val widgetReason: String
)

internal interface PlaybackAutoSkipTargets {
    fun nextCustomPosition(song: SongItem, positionMs: Long, durationMs: Long): Long?
    fun nextSponsorPosition(song: SongItem, positionMs: Long, durationMs: Long): Long?
}

internal object BiliPlaybackAutoSkipTargets : PlaybackAutoSkipTargets {
    override fun nextCustomPosition(song: SongItem, positionMs: Long, durationMs: Long): Long? =
        BiliVideoSkipPlaybackController.nextSkipPosition(song, positionMs, durationMs)

    override fun nextSponsorPosition(song: SongItem, positionMs: Long, durationMs: Long): Long? =
        BiliSponsorBlockPlaybackController.nextSkipPosition(song, positionMs, durationMs)
}

internal class PlaybackAutoSkipPolicy(private val targets: PlaybackAutoSkipTargets) {
    fun resolve(
        song: SongItem?,
        positionMs: Long,
        durationMs: Long,
        listenTogetherActive: Boolean
    ): PlaybackAutoSkipDecision? {
        if (song == null || listenTogetherActive) return null
        val customPosition = targets.nextCustomPosition(song, positionMs, durationMs)
        if (customPosition != null) {
            return PlaybackAutoSkipDecision(
                customPosition,
                BiliSkipSegmentSource.CUSTOM_INTERVAL,
                "BiliVideoSkip",
                "auto skipping interval",
                "bili_video_auto_skip"
            )
        }
        val sponsorPosition = targets.nextSponsorPosition(song, positionMs, durationMs)
        return sponsorPosition?.let {
            PlaybackAutoSkipDecision(
                it,
                BiliSkipSegmentSource.SPONSOR_BLOCK,
                "BiliSponsorBlock",
                "auto skipping segment",
                "bili_sponsor_block_auto_skip"
            )
        }
    }
}
