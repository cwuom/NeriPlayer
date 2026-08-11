package moe.ouom.neriplayer.data.local.database.store

import com.google.gson.Gson
import moe.ouom.neriplayer.data.local.database.entity.LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens

internal data class LocalPlaylistRoomSnapshot(
    val playlists: List<LocalPlaylistEntity>,
    val tracks: List<TrackEntity>,
    val members: List<PlaylistMemberEntity>,
    val memberTokens: List<PlaylistMemberTokenEntity>,
    val migrationMetadata: List<MigrationMetadataEntity>
)

internal data class LocalPlaylistRoomValidationResult(
    val equivalent: Boolean,
    val playlistCount: Int,
    val memberCount: Int,
    val firstMismatch: String?
)

internal class LocalPlaylistRoomMapper(
    private val gson: Gson = Gson()
) {
    fun toSnapshot(
        playlists: List<LocalPlaylist>,
        sourceDigest: String? = null,
        now: Long = System.currentTimeMillis()
    ): LocalPlaylistRoomSnapshot {
        val trackMap = LinkedHashMap<String, TrackEntity>()
        val playlistEntities = ArrayList<LocalPlaylistEntity>(playlists.size)
        val memberEntities = ArrayList<PlaylistMemberEntity>()
        val tokenEntities = ArrayList<PlaylistMemberTokenEntity>()

        playlists.forEachIndexed { playlistIndex, playlist ->
            playlistEntities += playlist.toEntity(playlistIndex)
            playlist.songs.forEachIndexed { songIndex, song ->
                val identity = song.identity()
                val identityKey = identity.stableKey()
                trackMap.putIfAbsent(identityKey, song.toTrackEntity(identityKey))
                memberEntities += song.toMemberEntity(
                    playlistId = playlist.id,
                    identityKey = identityKey,
                    displayPosition = songIndex
                )
                song.syncMembershipTokens
                    .normalizedSyncCausalTokens()
                    .forEachIndexed { tokenIndex, token ->
                        tokenEntities += PlaylistMemberTokenEntity(
                            playlistId = playlist.id,
                            identityKey = identityKey,
                            deviceId = token.deviceId,
                            counter = token.counter,
                            tokenIndex = tokenIndex
                        )
                    }
            }
        }

        return LocalPlaylistRoomSnapshot(
            playlists = playlistEntities,
            tracks = trackMap.values.toList(),
            members = memberEntities,
            memberTokens = tokenEntities,
            migrationMetadata = buildMigrationMetadata(sourceDigest, now)
        )
    }

    fun toDomain(
        playlists: List<LocalPlaylistEntity>,
        tracks: List<TrackEntity>,
        members: List<PlaylistMemberEntity>,
        memberTokens: List<PlaylistMemberTokenEntity>
    ): List<LocalPlaylist> {
        val tracksByIdentity = tracks.associateBy(TrackEntity::identityKey)
        val membersByPlaylist = members.groupBy(PlaylistMemberEntity::playlistId)
        val tokensByMember = memberTokens.groupBy { token ->
            PlaylistMemberKey(token.playlistId, token.identityKey)
        }

        return playlists
            .sortedWith(compareBy<LocalPlaylistEntity> { it.displayPosition }.thenBy { it.playlistId })
            .map { playlist ->
                val songs = membersByPlaylist[playlist.playlistId]
                    .orEmpty()
                    .sortedWith(
                        compareBy<PlaylistMemberEntity> { it.displayPosition }
                            .thenByDescending { it.addedAt }
                            .thenBy { it.orderTieBreak }
                            .thenBy { it.identityKey }
                    )
                    .map { member ->
                        val track = requireNotNull(tracksByIdentity[member.identityKey]) {
                            "Missing track row for ${member.identityKey}"
                        }
                        member.toSongItem(
                            track = track,
                            tokens = tokensByMember[
                                PlaylistMemberKey(member.playlistId, member.identityKey)
                            ].orEmpty()
                        )
                    }
                    .toMutableList()
                LocalPlaylist(
                    id = playlist.playlistId,
                    name = playlist.name,
                    songs = songs,
                    modifiedAt = playlist.modifiedAt,
                    customCoverUrl = playlist.customCoverUrl,
                    songOrderVersion = playlist.songOrderVersion
                )
            }
    }

    fun validateRoundTrip(playlists: List<LocalPlaylist>): LocalPlaylistRoomValidationResult {
        val snapshot = toSnapshot(playlists)
        val roundTrip = toDomain(
            playlists = snapshot.playlists,
            tracks = snapshot.tracks,
            members = snapshot.members,
            memberTokens = snapshot.memberTokens
        )
        val expected = playlists.map { playlist ->
            playlist.copy(
                songs = playlist.songs.mapTo(mutableListOf()) { song ->
                    song.copy(
                        streamUrl = null,
                        syncMembershipTokens = song.syncMembershipTokens.normalizedSyncCausalTokens()
                    )
                }
            )
        }
        return LocalPlaylistRoomValidationResult(
            equivalent = expected == roundTrip,
            playlistCount = snapshot.playlists.size,
            memberCount = snapshot.members.size,
            firstMismatch = firstMismatch(expected, roundTrip)
        )
    }

    private fun LocalPlaylist.toEntity(displayPosition: Int): LocalPlaylistEntity {
        return LocalPlaylistEntity(
            playlistId = id,
            name = name,
            displayPosition = displayPosition,
            customCoverUrl = customCoverUrl,
            modifiedAt = modifiedAt,
            songOrderVersion = songOrderVersion,
            isSystem = id < 0L
        )
    }

    private fun SongItem.toTrackEntity(identityKey: String): TrackEntity {
        val identity = identity()
        return TrackEntity(
            identityKey = identityKey,
            identityId = identity.id,
            identityAlbum = identity.album,
            identityMediaUri = identity.mediaUri,
            songId = id,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl,
            mediaUri = mediaUri,
            channelId = channelId,
            audioId = audioId,
            subAudioId = subAudioId,
            sourceStableKey = sourceStableKey,
            localFileName = localFileName,
            localFilePath = localFilePath,
            payloadSchemaVersion = LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION,
            durablePayloadJson = gson.toJson(withoutTransientDatabaseState())
        )
    }

    private fun SongItem.toMemberEntity(
        playlistId: Long,
        identityKey: String,
        displayPosition: Int
    ): PlaylistMemberEntity {
        return PlaylistMemberEntity(
            playlistId = playlistId,
            identityKey = identityKey,
            displayPosition = displayPosition,
            addedAt = addedAt,
            orderTieBreak = displayPosition,
            playlistContextId = playlistContextId,
            memberPayloadSchemaVersion = LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION,
            memberPayloadJson = gson.toJson(withoutTransientDatabaseState())
        )
    }

    private fun PlaylistMemberEntity.toSongItem(
        track: TrackEntity,
        tokens: List<PlaylistMemberTokenEntity>
    ): SongItem {
        val payload = requireNotNull(
            decodeSong(memberPayloadJson) ?: decodeSong(track.durablePayloadJson)
        ) {
            "Song payload is missing for ${track.identityKey}"
        }
        return payload.copy(
            addedAt = addedAt,
            playlistContextId = playlistContextId,
            syncMembershipTokens = tokens
                .sortedWith(
                    compareBy<PlaylistMemberTokenEntity> { it.tokenIndex }
                        .thenBy { it.deviceId }
                        .thenBy { it.counter }
                )
                .map { token ->
                    SyncCausalToken(
                        deviceId = token.deviceId,
                        counter = token.counter
                    )
                },
            streamUrl = null
        )
    }

    private fun decodeSong(payload: String): SongItem? {
        return runCatching {
            gson.fromJson(payload, SongItem::class.java)
        }.getOrNull()
    }

    private fun SongItem.withoutTransientDatabaseState(): SongItem {
        return copy(streamUrl = null)
    }

    private fun buildMigrationMetadata(
        sourceDigest: String?,
        now: Long
    ): List<MigrationMetadataEntity> {
        return buildList {
            add(
                MigrationMetadataEntity(
                    key = "local_playlist_import_schema",
                    value = LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION.toString(),
                    updatedAt = now
                )
            )
            add(
                MigrationMetadataEntity(
                    key = "local_playlist_cutover_state",
                    value = "shadow",
                    updatedAt = now
                )
            )
            sourceDigest?.let { digest ->
                add(
                    MigrationMetadataEntity(
                        key = "local_playlist_source_digest",
                        value = digest,
                        updatedAt = now
                    )
                )
            }
        }
    }

    private fun firstMismatch(
        expected: List<LocalPlaylist>,
        actual: List<LocalPlaylist>
    ): String? {
        if (expected == actual) return null
        if (expected.size != actual.size) {
            return "playlist count expected=${expected.size} actual=${actual.size}"
        }
        expected.zip(actual).forEachIndexed { index, (left, right) ->
            if (left != right) return "playlist[$index] expected=$left actual=$right"
        }
        return "unknown mismatch"
    }

    private data class PlaylistMemberKey(
        val playlistId: Long,
        val identityKey: String
    )
}
