package moe.ouom.neriplayer.core.player.audio.output

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer.settingsRepo
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.SongItem

internal object PlayerManagerPlaybackQualityPort : PlaybackQualityPort {
    override fun currentAudioSource(): PlaybackAudioSource? =
        PlayerManager.currentPlaybackAudioInfoFlow.value?.source

    override suspend fun persistPreferredQuality(source: PlaybackAudioSource, key: String) {
        if (source == PlaybackAudioSource.LOCAL) return
        writerFor(source)(key)
    }

    private fun writerFor(source: PlaybackAudioSource): suspend (String) -> Unit {
        val repository = settingsRepo
        val writers: Map<PlaybackAudioSource, suspend (String) -> Unit> = mapOf(
            PlaybackAudioSource.NETEASE to repository::setAudioQuality,
            PlaybackAudioSource.BILIBILI to repository::setBiliAudioQuality,
            PlaybackAudioSource.YOUTUBE_MUSIC to repository::setYouTubeAudioQuality
        )
        return writers.getValue(source)
    }

    override suspend fun refreshCurrentSong(source: PlaybackAudioSource, reason: String) {
        withContext(Dispatchers.Main) {
            val manager = PlayerManager
            if (currentRefreshSongIfSource(source) == null) return@withContext
            val positionMs = manager.player.currentPosition.coerceAtLeast(0L)
            manager.refreshCurrentSongUrl(
                resumePositionMs = positionMs,
                allowFallback = true,
                reason = reason,
                bypassCooldown = true,
                fallbackSeekPositionMs = positionMs,
                // USB 重配置保留的恢复意图不能让音质刷新提前恢复播放
                resumePlaybackAfterRefresh = manager.player.playWhenReady || manager.player.isPlaying,
                resumedPlaybackCommandSource = manager.activePlaybackCommandSource
            )
        }
    }

    private fun currentRefreshSongIfSource(source: PlaybackAudioSource): SongItem? =
        if (currentAudioSource() == source) currentRemoteSong() else null

    private fun currentRemoteSong(): SongItem? {
        val song = PlayerManager.currentSongFlow.value ?: return null
        return remoteSongOrNull(song)
    }

    private fun remoteSongOrNull(song: SongItem): SongItem? =
        if (PlayerManager.isLocalSong(song)) null else song
}
