package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistSongEntity
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistSongNeteaseArtistEntity
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylist

internal enum class FavoritePlaylistRoomImportStatus {
    IMPORTED,
    SKIPPED_ALREADY_PRIMARY
}

internal data class FavoritePlaylistRoomImportResult(
    val status: FavoritePlaylistRoomImportStatus,
    val playlistCount: Int
)

internal class FavoritePlaylistRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun readIfRoomPrimary(): List<FavoritePlaylist>? {
        if (database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value != ROOM_PRIMARY_STATE
        ) {
            return null
        }
        return database.withTransaction { readPlaylistsUnsafe() }
    }

    suspend fun importLegacyAndPromote(
        favorites: List<FavoritePlaylist>,
        now: Long = System.currentTimeMillis()
    ): FavoritePlaylistRoomImportResult {
        return database.withTransaction {
            val cutoverState = database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value
            if (cutoverState == ROOM_PRIMARY_STATE) {
                return@withTransaction FavoritePlaylistRoomImportResult(
                    status = FavoritePlaylistRoomImportStatus.SKIPPED_ALREADY_PRIMARY,
                    playlistCount = favorites.size
                )
            }
            database.favoritePlaylistDao().deleteAllSongs()
            database.favoritePlaylistDao().deleteAllSongNeteaseArtists()
            database.favoritePlaylistDao().deleteAllPlaylists()
            insertAll(favorites)
            markRoomPrimary(now)
            FavoritePlaylistRoomImportResult(
                status = FavoritePlaylistRoomImportStatus.IMPORTED,
                playlistCount = favorites.size
            )
        }
    }

    suspend fun promoteExistingAndRead(
        now: Long = System.currentTimeMillis()
    ): List<FavoritePlaylist> {
        return database.withTransaction {
            val favorites = readPlaylistsUnsafe()
            markRoomPrimary(now)
            favorites
        }
    }

    suspend fun replaceAll(
        favorites: List<FavoritePlaylist>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            database.favoritePlaylistDao().deleteAllSongs()
            database.favoritePlaylistDao().deleteAllSongNeteaseArtists()
            database.favoritePlaylistDao().deleteAllPlaylists()
            insertAll(favorites)
            markRoomPrimary(now)
        }
    }

    suspend fun writeIncremental(
        previous: List<FavoritePlaylist>,
        next: List<FavoritePlaylist>,
        now: Long = System.currentTimeMillis()
    ) {
        val previousByKey = previous.associateBy(FavoritePlaylist::storageKey)
        val nextByKey = next.associateBy(FavoritePlaylist::storageKey)
        val changedKeys = (previousByKey.keys + nextByKey.keys)
            .filter { key -> previousByKey[key] != nextByKey[key] }
            .toSet()
        if (changedKeys.isEmpty()) {
            return
        }
        val removedKeys = changedKeys.filter { it !in nextByKey }
        val changedFavorites = next.filter { it.storageKey() in changedKeys }
        database.withTransaction {
            val dao = database.favoritePlaylistDao()
            removedKeys.forEach { key ->
                dao.deletePlaylist(key.playlistId, key.source)
            }
            changedFavorites.forEach { favorite ->
                dao.deleteSongs(favorite.id, favorite.source)
                dao.deleteSongNeteaseArtists(favorite.id, favorite.source)
            }
            dao.upsertPlaylists(changedFavorites.map(FavoritePlaylist::toEntity))
            dao.upsertSongs(
                changedFavorites.flatMap { favorite ->
                    favorite.toSongEntities()
                }
            )
            dao.upsertSongNeteaseArtists(
                changedFavorites.flatMap { favorite ->
                    favorite.toSongNeteaseArtistEntities()
                }
            )
            markRoomPrimary(now)
        }
    }

    private suspend fun readPlaylistsUnsafe(): List<FavoritePlaylist> {
        val songsByKey = database.favoritePlaylistDao()
            .getSongs()
            .groupBy { song -> song.playlistId to song.source }
            .mapValues { (_, songs) ->
                songs.sortedBy(FavoritePlaylistSongEntity::displayPosition)
            }
        val artistsByKey = database.favoritePlaylistDao()
            .getSongNeteaseArtists()
            .groupBy { artist ->
                FavoritePlaylistSongKey(
                    artist.playlistId,
                    artist.source,
                    artist.displayPosition
                )
            }
        return database.favoritePlaylistDao().getPlaylists().map { playlist ->
            FavoritePlaylist(
                id = playlist.playlistId,
                name = playlist.name,
                coverUrl = playlist.coverUrl,
                trackCount = playlist.trackCount,
                source = playlist.source,
                browseId = playlist.browseId,
                playlistId = playlist.remotePlaylistId,
                subtitle = playlist.subtitle,
                songs = songsByKey[playlist.playlistId to playlist.source]
                    .orEmpty()
                    .map { song ->
                        song.toSongItem().copy(
                            neteaseArtists = artistsByKey[
                                FavoritePlaylistSongKey(
                                    playlist.playlistId,
                                    playlist.source,
                                    song.displayPosition
                                )
                            ].orEmpty()
                                .sortedBy(FavoritePlaylistSongNeteaseArtistEntity::artistPosition)
                                .map { artist ->
                                    NeteaseArtistSummary(
                                        id = artist.artistId,
                                        name = artist.artistName
                                    )
                                }
                        )
                    },
                addedTime = playlist.addedTime,
                sortOrder = playlist.sortOrder,
                modifiedAt = playlist.modifiedAt,
                isDeleted = playlist.isDeleted
            )
        }
    }

    private suspend fun insertAll(favorites: List<FavoritePlaylist>) {
        val normalized = favorites
            .groupBy { it.id to it.source }
            .map { (_, snapshots) ->
                snapshots.maxByOrNull { maxOf(it.modifiedAt, it.addedTime) }!!
            }
        database.favoritePlaylistDao().upsertPlaylists(
            normalized.map(FavoritePlaylist::toEntity)
        )
        database.favoritePlaylistDao().upsertSongs(
            normalized.flatMap { favorite -> favorite.toSongEntities() }
        )
        database.favoritePlaylistDao().upsertSongNeteaseArtists(
            normalized.flatMap { favorite -> favorite.toSongNeteaseArtistEntities() }
        )
    }

    private fun FavoritePlaylist.toSongEntities(): List<FavoritePlaylistSongEntity> {
        return songs.mapIndexed { index, song ->
            FavoritePlaylistSongEntity(
                playlistId = id,
                source = source,
                displayPosition = index,
                id = song.id,
                name = song.name,
                artist = song.artist,
                album = song.album,
                albumId = song.albumId,
                durationMs = song.durationMs,
                coverUrl = song.coverUrl,
                mediaUri = song.mediaUri,
                matchedLyric = song.matchedLyric,
                matchedTranslatedLyric = song.matchedTranslatedLyric,
                matchedLyricSource = song.matchedLyricSource?.name,
                matchedSongId = song.matchedSongId,
                userLyricOffsetMs = song.userLyricOffsetMs,
                customCoverUrl = song.customCoverUrl,
                customName = song.customName,
                customArtist = song.customArtist,
                originalName = song.originalName,
                originalArtist = song.originalArtist,
                originalCoverUrl = song.originalCoverUrl,
                originalLyric = song.originalLyric,
                originalTranslatedLyric = song.originalTranslatedLyric,
                localFileName = song.localFileName,
                localFilePath = song.localFilePath,
                channelId = song.channelId,
                audioId = song.audioId,
                subAudioId = song.subAudioId,
                playlistContextId = song.playlistContextId,
                sourceStableKey = song.sourceStableKey,
                addedAt = song.addedAt,
                structuredSchemaVersion = 2
            )
        }
    }

    private fun FavoritePlaylist.toSongNeteaseArtistEntities(): List<FavoritePlaylistSongNeteaseArtistEntity> {
        return songs.flatMapIndexed { songPosition, song ->
            song.neteaseArtists.orEmpty().mapIndexed { artistPosition, artist ->
                FavoritePlaylistSongNeteaseArtistEntity(
                    playlistId = id,
                    source = source,
                    displayPosition = songPosition,
                    artistPosition = artistPosition,
                    artistId = artist.id,
                    artistName = artist.name
                )
            }
        }
    }

    private suspend fun markRoomPrimary(now: Long) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(IMPORT_SCHEMA_METADATA_KEY, "1", now)
        )
    }

    private fun metadata(key: String, value: String, now: Long) =
        moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity(
            key = key,
            value = value,
            updatedAt = now
        )

    companion object {
        const val CUTOVER_STATE_METADATA_KEY = "favorite_playlist_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "favorite_playlist_import_schema"
        const val ROOM_PRIMARY_STATE = "room_primary"
    }
}

private data class FavoritePlaylistStorageKey(
    val playlistId: Long,
    val source: String
)

private data class FavoritePlaylistSongKey(
    val playlistId: Long,
    val source: String,
    val displayPosition: Int
)

private fun FavoritePlaylistSongEntity.toSongItem(): SongItem {
    return SongItem(
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
        sourceStableKey = sourceStableKey,
        addedAt = addedAt,
        streamUrl = null
    )
}

private fun FavoritePlaylist.storageKey(): FavoritePlaylistStorageKey {
    return FavoritePlaylistStorageKey(playlistId = id, source = source)
}

private fun FavoritePlaylist.toEntity(): FavoritePlaylistEntity {
    return FavoritePlaylistEntity(
        playlistId = id,
        source = source,
        name = name,
        coverUrl = coverUrl,
        trackCount = trackCount,
        browseId = browseId,
        remotePlaylistId = playlistId,
        subtitle = subtitle,
        addedTime = addedTime,
        sortOrder = sortOrder,
        modifiedAt = modifiedAt,
        isDeleted = isDeleted
    )
}
