package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import com.google.gson.Gson
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.FavoritePlaylistSongEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylist

internal class FavoritePlaylistRoomStore(
    private val database: NeriUserDataDatabase,
    private val gson: Gson = Gson()
) {
    suspend fun readIfRoomPrimary(): List<FavoritePlaylist>? {
        if (database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value != ROOM_PRIMARY_STATE
        ) {
            return null
        }
        return readPlaylists()
    }

    suspend fun importLegacyAndPromote(
        favorites: List<FavoritePlaylist>,
        now: Long = System.currentTimeMillis()
    ) {
        replaceAll(favorites, now)
    }

    suspend fun replaceAll(
        favorites: List<FavoritePlaylist>,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            database.favoritePlaylistDao().deleteAllSongs()
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
            }
            dao.upsertPlaylists(changedFavorites.map(FavoritePlaylist::toEntity))
            dao.upsertSongs(
                changedFavorites.flatMap { favorite ->
                    favorite.toSongEntities()
                }
            )
            markRoomPrimary(now)
        }
    }

    suspend fun markLegacyJsonPrimary(now: Long = System.currentTimeMillis()) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, now)
        )
    }

    private suspend fun readPlaylists(): List<FavoritePlaylist> {
        val songsByKey = database.favoritePlaylistDao()
            .getSongs()
            .groupBy { song -> song.playlistId to song.source }
            .mapValues { (_, songs) ->
                songs.sortedBy(FavoritePlaylistSongEntity::displayPosition)
                    .map { song ->
                        runCatching {
                            gson.fromJson(song.songPayloadJson, SongItem::class.java)
                        }.getOrElse { error ->
                            throw IllegalStateException(
                                "Invalid favorite song payload at " +
                                    "${song.playlistId}/${song.source}/" +
                                    "${song.displayPosition}",
                                error
                            )
                        }
                    }
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
                songs = songsByKey[playlist.playlistId to playlist.source].orEmpty(),
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
            normalized.flatMap { favorite ->
                favorite.toSongEntities()
            }
        )
    }

    private fun FavoritePlaylist.toSongEntities(): List<FavoritePlaylistSongEntity> {
        return songs.mapIndexed { index, song ->
            FavoritePlaylistSongEntity(
                playlistId = id,
                source = source,
                displayPosition = index,
                songPayloadJson = gson.toJson(song)
            )
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
        const val LEGACY_JSON_STATE = "legacy_json"
    }
}

private data class FavoritePlaylistStorageKey(
    val playlistId: Long,
    val source: String
)

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
