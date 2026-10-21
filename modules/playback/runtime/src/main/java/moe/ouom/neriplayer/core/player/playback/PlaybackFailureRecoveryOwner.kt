package moe.ouom.neriplayer.core.player.playback

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.network.holdPlaybackForNetworkIfOffline
import moe.ouom.neriplayer.core.player.prefetch.clearPlaybackDemandCacheKey
import moe.ouom.neriplayer.core.player.url.invalidateCachedResourceForPlaybackRecovery
import moe.ouom.neriplayer.core.player.url.playbackFailureSongDescription
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.common.R as CoreCommonR

internal enum class PlaybackResolutionFailure(val reason: String) {
    LOGIN_REQUIRED("resolve_song_url_requires_login"),
    URL_UNAVAILABLE("resolve_song_url_failure")
}

internal interface PlaybackFailureRecoveryPort {
    val requestToken: Long
    val resumePlaybackRequested: Boolean
    fun reportResolutionFailure(failure: PlaybackResolutionFailure)
    fun recordFailure()
    fun stopPreservingQueue()
    fun advanceAfterFailure(reason: String, commandSource: PlaybackCommandSource)
    suspend fun invalidateCache(key: String, reason: String, shouldApplyMutation: () -> Boolean)

    /** 断网时停在当前曲目等网络，不让每一首都解析失败后把整个队列跳光 */
    fun holdForNetwork(reason: String): Boolean = false
}

internal class PlaybackFailureRecoveryOwner(private val port: PlaybackFailureRecoveryPort) {
    fun onResolutionFailure(
        requestToken: Long,
        failure: PlaybackResolutionFailure,
        commandSource: PlaybackCommandSource
    ) {
        if (requestToken != port.requestToken) return
        port.reportResolutionFailure(failure)
        if (!port.resumePlaybackRequested) {
            port.stopPreservingQueue()
            return
        }
        if (failure == PlaybackResolutionFailure.URL_UNAVAILABLE && port.holdForNetwork(failure.reason)) return
        port.recordFailure()
        port.advanceAfterFailure(failure.reason, commandSource)
    }

    suspend fun onPlayerFailure(
        requestToken: Long,
        reason: String,
        commandSource: PlaybackCommandSource,
        cacheKeyToInvalidate: String?
    ) {
        val recoveryContext = currentCoroutineContext()
        recoveryContext.ensureActive()
        if (!canRecover(requestToken)) return
        cacheKeyToInvalidate?.let { key ->
            port.invalidateCache(key, reason) {
                recoveryContext.isActive && canRecover(requestToken)
            }
        }
        recoveryContext.ensureActive()
        if (!canRecover(requestToken)) return
        if (port.holdForNetwork(reason)) return
        port.advanceAfterFailure(reason, commandSource)
    }

    private fun canRecover(requestToken: Long): Boolean =
        requestToken == port.requestToken && port.resumePlaybackRequested
}

internal val playbackFailureRecoveryOwner = PlaybackFailureRecoveryOwner(
    object : PlaybackFailureRecoveryPort {
        override val requestToken: Long get() = PlayerManager.playbackRequestToken
        override val resumePlaybackRequested: Boolean get() = PlayerManager.resumePlaybackRequested

        override fun reportResolutionFailure(failure: PlaybackResolutionFailure) {
            val songDescription = PlayerManager._currentSongFlow.value?.let {
                playbackFailureSongDescription(it, PlayerManager.playbackRequestToken)
            } ?: "no current song"
            when (failure) {
                PlaybackResolutionFailure.LOGIN_REQUIRED -> {
                    PlayerManager.clearPlaybackDemandCacheKey("play_at_index_requires_login")
                    NPLogger.w(
                        "NERI-PlayerManager",
                        "Requires login to play: $songDescription"
                    )
                    PlayerManager.postPlayerEvent(
                        PlayerEvent.ShowLoginPrompt(
                            PlayerManager.getLocalizedString(CoreCommonR.string.player_playback_login_required)
                        )
                    )
                }
                PlaybackResolutionFailure.URL_UNAVAILABLE -> {
                    PlayerManager.clearPlaybackDemandCacheKey("play_at_index_failure")
                    NPLogger.e(
                        "NERI-PlayerManager",
                        "获取播放地址失败: LocalPlaybackTrace stage=url_failure $songDescription"
                    )
                }
            }
        }

        override fun recordFailure() {
            PlayerManager.consecutivePlayFailures++
        }

        override fun stopPreservingQueue() {
            PlayerManager.stopPlaybackPreservingQueue(clearMediaUrl = true)
        }

        override fun advanceAfterFailure(reason: String, commandSource: PlaybackCommandSource) {
            PlayerManager.advanceAfterPlaybackFailure(reason, commandSource)
        }

        override suspend fun invalidateCache(
            key: String,
            reason: String,
            shouldApplyMutation: () -> Boolean
        ) {
            PlayerManager.invalidateCachedResourceForPlaybackRecovery(key, reason, shouldApplyMutation)
        }

        override fun holdForNetwork(reason: String): Boolean =
            PlayerManager.holdPlaybackForNetworkIfOffline(reason)
    }
)
