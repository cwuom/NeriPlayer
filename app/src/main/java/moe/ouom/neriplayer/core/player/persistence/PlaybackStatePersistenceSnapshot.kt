package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.core.player.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.model.toPersistedSongItem
import moe.ouom.neriplayer.core.player.model.withPlaybackState
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sameIdentityAs

internal enum class PlaybackStateWrite {
    NONE,
    CLEAR,
    REPLACE_QUEUE,
    UPDATE_PLAYBACK
}

internal class PlaybackStatePersistenceSnapshot(
    val queue: PlayerQueueSnapshot,
    val playback: PersistedPlaybackState,
    shuffleRestorePlaylist: List<SongItem>?,
    shuffleRestoreIndex: Int
) {
    private val shuffleRestorePlaylist = shuffleRestorePlaylist
        ?.takeIf { playback.shuffleEnabled == true }
        ?.toList()
    private val shuffleRestoreIndex = if (playback.shuffleEnabled == true) {
        val currentSong = queue.playlist.getOrNull(queue.currentIndex)
        this.shuffleRestorePlaylist?.indexOfFirst { currentSong?.sameIdentityAs(it) == true }
            ?.takeIf { it >= 0 } ?: shuffleRestoreIndex.takeIf { it >= 0 }
    } else {
        null
    }

    init {
        require(playback.index == queue.currentIndex)
        require(playback.positionMs >= 0L)
    }

    fun writeAfter(previous: PlaybackStatePersistenceSnapshot?): PlaybackStateWrite {
        if (queue.playlist.isEmpty()) {
            return if (previous?.queue?.playlist?.isEmpty() == true && playback == previous.playback) {
                PlaybackStateWrite.NONE
            } else {
                PlaybackStateWrite.CLEAR
            }
        }
        if (previous == null || queue.playlist !== previous.queue.playlist ||
            shuffleRestorePlaylist != previous.shuffleRestorePlaylist ||
            shuffleRestoreIndex != previous.shuffleRestoreIndex
        ) {
            return PlaybackStateWrite.REPLACE_QUEUE
        }
        return if (playback != previous.playback) {
            PlaybackStateWrite.UPDATE_PLAYBACK
        } else {
            PlaybackStateWrite.NONE
        }
    }

    fun toPersistedState(): PersistedState {
        val currentSong = queue.playlist.getOrNull(queue.currentIndex)
        return PersistedState(
            playlist = queue.playlist.mapIndexed { index, song ->
                // 仅保存当前歌曲的内嵌歌词，避免反复序列化整队长文本
                song.toPersistedSongItem(includeLyrics = index == queue.currentIndex)
            },
            index = queue.currentIndex,
            shuffleRestorePlaylist = shuffleRestorePlaylist?.map { song ->
                song.toPersistedSongItem(includeLyrics = currentSong?.sameIdentityAs(song) == true)
            },
            shuffleRestoreIndex = shuffleRestoreIndex
        ).withPlaybackState(playback)
    }
}
