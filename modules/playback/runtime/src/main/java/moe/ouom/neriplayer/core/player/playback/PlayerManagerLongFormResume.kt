package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.model.SongItem

/** 补跳转只在播放刚开始时生效，用户此后自己拖动的进度不会被覆盖 */
internal const val LATE_LONG_FORM_RESUME_MAX_ELAPSED_MS = 3_000L

internal fun shouldApplyLateLongFormResume(
    sameRequest: Boolean,
    sameSong: Boolean,
    currentPositionMs: Long,
    rememberedPositionMs: Long
): Boolean = sameRequest && sameSong &&
    currentPositionMs <= LATE_LONG_FORM_RESUME_MAX_ELAPSED_MS &&
    rememberedPositionMs > currentPositionMs

/**
 * 冷启动后播放历史在后台加载，立即开始的长音频查不到上次位置只能从头播放；
 * 历史加载完成后，若仍是同一次播放且几乎没有前进，再按记录的位置补一次跳转
 */
internal fun PlayerManager.resumeLongFormWhenHistoryLoads(
    song: SongItem,
    allowRememberedPosition: Boolean,
    requestToken: Long
) {
    val history = PlayerDependencies.repositories.playHistoryRepo
    if (!allowRememberedPosition || history.isHistoryLoaded) return
    val positionGeneration = playbackPositionGeneration
    mainScope.launch {
        if (!history.awaitHistoryLoaded()) return@launch
        if (playbackPositionGeneration != positionGeneration) return@launch
        val currentPositionMs = playbackPositionFlow.value
        val rememberedPositionMs = resolveRememberedLongFormPlaybackStartPosition(
            song = song,
            requestedPositionMs = 0L,
            allowRememberedPosition = true
        )
        if (
            shouldApplyLateLongFormResume(
                sameRequest = playbackRequestToken == requestToken,
                sameSong = currentSongFlow.value?.sameIdentityAs(song) == true,
                currentPositionMs = currentPositionMs,
                rememberedPositionMs = rememberedPositionMs
            )
        ) {
            seekTo(rememberedPositionMs)
        }
    }
}
