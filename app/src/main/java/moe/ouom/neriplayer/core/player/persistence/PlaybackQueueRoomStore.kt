package moe.ouom.neriplayer.core.player.persistence

import androidx.room.withTransaction
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PersistedSongItem
import moe.ouom.neriplayer.core.player.model.PersistedState
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PLAYBACK_QUEUE_MAIN
import moe.ouom.neriplayer.data.local.database.entity.PLAYBACK_QUEUE_SHUFFLE_RESTORE
import moe.ouom.neriplayer.data.local.database.entity.PLAYBACK_QUEUE_STATE_ID
import moe.ouom.neriplayer.data.local.database.entity.PlaybackQueueSongEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaybackQueueStateEntity

internal class PlaybackQueueRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun isRoomPrimary(): Boolean {
        return database.syncMetadataDao()
            .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
            ?.value == ROOM_PRIMARY_STATE
    }

    suspend fun readIfRoomPrimary(): PersistedState? {
        if (!isRoomPrimary()) {
            return null
        }
        return readState()
    }

    suspend fun replaceSnapshot(
        state: PersistedState,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val dao = database.playbackQueueDao()
            dao.deleteSongs()
            dao.upsertSongs(
                state.playlist.mapIndexed { position, song ->
                    song.toEntity(PLAYBACK_QUEUE_MAIN, position)
                } + state.shuffleRestorePlaylist.orEmpty().mapIndexed { position, song ->
                    song.toEntity(PLAYBACK_QUEUE_SHUFFLE_RESTORE, position)
                }
            )
            dao.upsertState(state.toEntity(now))
            markRoomPrimary(now)
        }
    }

    suspend fun updatePlaybackState(
        state: PersistedPlaybackState,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val dao = database.playbackQueueDao()
            val previous = dao.getState()
            dao.upsertState(
                state.toEntity(
                    now = now,
                    shuffleRestoreIndex = previous?.shuffleRestoreIndex
                        ?.takeIf { state.shuffleEnabled == true }
                )
            )
            markRoomPrimary(now)
        }
    }

    suspend fun clear(now: Long = System.currentTimeMillis()) {
        database.withTransaction {
            database.playbackQueueDao().deleteState()
            database.playbackQueueDao().deleteSongs()
            markRoomPrimary(now)
        }
    }

    private suspend fun readState(): PersistedState? {
        return database.withTransaction {
            val dao = database.playbackQueueDao()
            val state = dao.getState() ?: return@withTransaction null
            PersistedState(
                playlist = dao.getSongs(PLAYBACK_QUEUE_MAIN).map(PlaybackQueueSongEntity::toPersistedSong),
                index = state.currentIndex,
                mediaUrl = state.mediaUrl,
                positionMs = state.positionMs,
                shouldResumePlayback = state.shouldResumePlayback,
                repeatMode = state.repeatMode,
                shuffleEnabled = state.shuffleEnabled,
                shuffleRestorePlaylist = if (state.shuffleEnabled == true) {
                    dao.getSongs(PLAYBACK_QUEUE_SHUFFLE_RESTORE)
                        .map(PlaybackQueueSongEntity::toPersistedSong)
                } else {
                    null
                },
                shuffleRestoreIndex = state.shuffleRestoreIndex
            )
        }
    }

    private suspend fun markRoomPrimary(now: Long) {
        database.syncMetadataDao().upsertMigrationMetadata(
            MigrationMetadataEntity(
                key = CUTOVER_STATE_METADATA_KEY,
                value = ROOM_PRIMARY_STATE,
                updatedAt = now
            )
        )
    }

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "playback_queue_cutover_state"
        const val ROOM_PRIMARY_STATE = "room_primary"
    }
}

private fun PersistedSongItem.toEntity(
    queueId: String,
    position: Int
): PlaybackQueueSongEntity {
    return PlaybackQueueSongEntity(
        queueId = queueId,
        position = position,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedLyricSource = matchedLyricSource?.name,
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        streamUrl = streamUrl
    )
}

private fun PlaybackQueueSongEntity.toPersistedSong(): PersistedSongItem {
    return PersistedSongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedLyricSource = matchedLyricSource?.let { value ->
            runCatching { MusicPlatform.valueOf(value) }.getOrNull()
        },
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        streamUrl = streamUrl
    )
}

private fun PersistedState.toEntity(now: Long): PlaybackQueueStateEntity {
    return PlaybackQueueStateEntity(
        id = PLAYBACK_QUEUE_STATE_ID,
        currentIndex = index,
        mediaUrl = mediaUrl,
        positionMs = positionMs,
        shouldResumePlayback = shouldResumePlayback,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        shuffleRestoreIndex = shuffleRestoreIndex,
        updatedAt = now
    )
}

private fun PersistedPlaybackState.toEntity(
    now: Long,
    shuffleRestoreIndex: Int? = null
): PlaybackQueueStateEntity {
    return PlaybackQueueStateEntity(
        id = PLAYBACK_QUEUE_STATE_ID,
        currentIndex = index,
        mediaUrl = mediaUrl,
        positionMs = positionMs,
        shouldResumePlayback = shouldResumePlayback,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        shuffleRestoreIndex = shuffleRestoreIndex,
        updatedAt = now
    )
}
