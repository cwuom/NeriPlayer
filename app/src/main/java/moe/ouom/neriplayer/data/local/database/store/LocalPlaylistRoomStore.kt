package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import com.google.gson.Gson
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxStatus
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutationOutbox
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey
import java.security.MessageDigest
import java.util.UUID
import moe.ouom.neriplayer.core.logging.NPLogger

internal enum class LocalPlaylistRoomShadowImportStatus {
    IMPORTED,
    SKIPPED_UNCHANGED,
    SKIPPED_NOT_EQUIVALENT
}

internal data class LocalPlaylistRoomShadowImportResult(
    val status: LocalPlaylistRoomShadowImportStatus,
    val playlistCount: Int,
    val memberCount: Int,
    val firstMismatch: String? = null
)

internal class LocalPlaylistRoomStore(
    private val database: NeriUserDataDatabase,
    private val gson: Gson = Gson()
) {
    private val mapper = LocalPlaylistRoomMapper()

    suspend fun isRoomPrimary(): Boolean {
        return database.syncMetadataDao()
            .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
            ?.value == ROOM_PRIMARY_STATE
    }

    suspend fun readIfRoomPrimary(): List<LocalPlaylist>? {
        if (!isRoomPrimary()) {
            return null
        }
        return readPlaylists()
    }

    suspend fun replacePlaylists(
        playlists: List<LocalPlaylist>,
        sourceDigest: String? = null,
        cutoverState: String = ROOM_PRIMARY_STATE
    ) {
        val snapshot = mapper.toSnapshot(
            playlists = playlists,
            sourceDigest = sourceDigest
        )
        database.withTransaction {
            database.localPlaylistDao().replaceSnapshot(
                playlists = snapshot.playlists,
                tracks = snapshot.tracks,
                members = snapshot.members,
                memberNeteaseArtists = snapshot.memberNeteaseArtists,
                memberTokens = snapshot.memberTokens
            )
            snapshot.migrationMetadata
                .map { metadata ->
                    if (metadata.key == CUTOVER_STATE_METADATA_KEY) {
                        metadata.copy(value = cutoverState)
                    } else {
                        metadata
                    }
                }
                .forEach { metadata ->
                    database.syncMetadataDao().upsertMigrationMetadata(metadata)
                }
        }
    }

    suspend fun writeIncremental(
        previous: List<LocalPlaylist>,
        next: List<LocalPlaylist>,
        sourceDigest: String,
        now: Long = System.currentTimeMillis()
    ) {
        val previousById = previous.associateBy(LocalPlaylist::id)
        val nextById = next.associateBy(LocalPlaylist::id)
        val previousPositions = previous.withIndex().associate { it.value.id to it.index }
        val nextPositions = next.withIndex().associate { it.value.id to it.index }
        val changedIds = (previousById.keys + nextById.keys)
            .filter { playlistId ->
                previousById[playlistId] != nextById[playlistId] ||
                    previousPositions[playlistId] != nextPositions[playlistId]
            }
            .toSet()
        if (changedIds.isEmpty()) {
            database.withTransaction {
                database.syncMetadataDao().upsertMigrationMetadata(
                    migrationMetadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
                )
                database.syncMetadataDao().upsertMigrationMetadata(
                    migrationMetadata(SOURCE_DIGEST_METADATA_KEY, sourceDigest, now)
                )
            }
            return
        }

        val removedIds = changedIds.filter { it !in nextById }
        val changedPlaylists = next
            .filter { it.id in changedIds }
            .map { playlist ->
                playlist to nextPositions.getValue(playlist.id)
            }
        val snapshot = mapper.toSnapshot(
            playlists = changedPlaylists.map { it.first },
            sourceDigest = sourceDigest,
            now = now
        )
        val tracksByIdentity = snapshot.tracks.associateBy { it.identityKey }
        val changedMembers = snapshot.members
        val changedMemberNeteaseArtists = snapshot.memberNeteaseArtists
        val changedTokens = snapshot.memberTokens

        database.withTransaction {
            removedIds.forEach { playlistId ->
                database.localPlaylistDao().deleteMemberTokensForPlaylist(playlistId)
                database.localPlaylistDao()
                    .deleteMemberNeteaseArtistsForPlaylist(playlistId)
                database.localPlaylistDao().deleteMembersForPlaylist(playlistId)
                database.localPlaylistDao().deletePlaylist(playlistId)
            }

            database.localPlaylistDao().insertPlaylists(
                changedPlaylists.map { (playlist, position) ->
                    snapshot.playlists
                        .first { it.playlistId == playlist.id }
                        .copy(displayPosition = position)
                }
            )

            changedIds
                .filter { it in nextById }
                .forEach { playlistId ->
                    database.localPlaylistDao().deleteMemberTokensForPlaylist(playlistId)
                    database.localPlaylistDao()
                        .deleteMemberNeteaseArtistsForPlaylist(playlistId)
                    database.localPlaylistDao().deleteMembersForPlaylist(playlistId)
                }
            database.localPlaylistDao().insertTracks(tracksByIdentity.values.toList())
            database.localPlaylistDao().insertMembers(changedMembers)
            database.localPlaylistDao().insertMemberNeteaseArtists(changedMemberNeteaseArtists)
            database.localPlaylistDao().insertMemberTokens(changedTokens)
            database.localPlaylistDao().deleteOrphanTracks()
            database.syncMetadataDao().upsertMigrationMetadata(
                migrationMetadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
            )
            database.syncMetadataDao().upsertMigrationMetadata(
                migrationMetadata(SOURCE_DIGEST_METADATA_KEY, sourceDigest, now)
            )
            database.syncMetadataDao().upsertMigrationMetadata(
                migrationMetadata(
                    IMPORT_SCHEMA_METADATA_KEY,
                    moe.ouom.neriplayer.data.local.database.entity
                        .LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION.toString(),
                    now
                )
            )
        }
    }

    suspend fun readPlaylists(): List<LocalPlaylist> {
        return database.withTransaction {
            mapper.toDomain(
                playlists = database.localPlaylistDao().getPlaylists(),
                tracks = database.localPlaylistDao().getTracks(),
                members = database.localPlaylistDao().getMembers(),
                memberNeteaseArtists = database.localPlaylistDao()
                    .getMemberNeteaseArtists(),
                memberTokens = database.localPlaylistDao().getMemberTokens()
            )
        }
    }

    suspend fun readPendingSyncMutationOutbox(): LocalPlaylistSyncMutationOutbox? {
        val entries = database.syncMetadataDao().getOutbox(
            statuses = listOf(SyncOutboxStatus.PENDING),
            limit = MAX_PENDING_OUTBOX_ENTRIES
        )
        if (entries.isEmpty()) {
            return null
        }
        return LocalPlaylistSyncMutationOutbox(
            mutations = entries.mapNotNull { entry ->
                runCatching {
                    gson.fromJson(
                        entry.mutationPayloadJson,
                        moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutation::class.java
                    )
                }.onFailure { error ->
                    NPLogger.w("LocalPlaylistRoomStore", "Skipping invalid sync outbox entry", error)
                }.getOrNull()
            }
        ).takeIf { it.mutations.isNotEmpty() }
    }

    suspend fun writePendingSyncMutationOutbox(
        outbox: LocalPlaylistSyncMutationOutbox,
        now: Long = System.currentTimeMillis()
    ) {
        require(outbox.mutations.size <= MAX_PENDING_OUTBOX_ENTRIES) {
            "Pending playlist sync outbox exceeds $MAX_PENDING_OUTBOX_ENTRIES entries"
        }
        database.withTransaction {
            database.syncMetadataDao().deleteOutboxByStatus(SyncOutboxStatus.PENDING)
            outbox.mutations.forEachIndexed { index, mutation ->
                database.syncMetadataDao().insertOutbox(
                    SyncOutboxEntity(
                        operationId = "playlist-mutation-${UUID.randomUUID()}-$index",
                        expectedDomainRevision = 0L,
                        payloadVersion = 1,
                        mutationPayloadJson = gson.toJson(mutation),
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun clearPendingSyncMutationOutbox() {
        database.syncMetadataDao().deleteOutboxByStatus(SyncOutboxStatus.PENDING)
    }

    fun validateRoundTrip(playlists: List<LocalPlaylist>): LocalPlaylistRoomValidationResult {
        return mapper.validateRoundTrip(playlists)
    }

    suspend fun importShadowSnapshotIfChanged(
        playlists: List<LocalPlaylist>,
        sourceDigest: String
    ): LocalPlaylistRoomShadowImportResult {
        val currentDigest = database.syncMetadataDao()
            .getMigrationMetadata(SOURCE_DIGEST_METADATA_KEY)
            ?.value
        if (currentDigest == sourceDigest) {
            return LocalPlaylistRoomShadowImportResult(
                status = LocalPlaylistRoomShadowImportStatus.SKIPPED_UNCHANGED,
                playlistCount = playlists.size,
                memberCount = playlists.sumOf { it.songs.size }
            )
        }

        val validation = mapper.validateRoundTrip(playlists)
        if (!validation.equivalent) {
            return LocalPlaylistRoomShadowImportResult(
                status = LocalPlaylistRoomShadowImportStatus.SKIPPED_NOT_EQUIVALENT,
                playlistCount = validation.playlistCount,
                memberCount = validation.memberCount,
                firstMismatch = validation.firstMismatch
            )
        }

        replacePlaylists(playlists, sourceDigest, cutoverState = "shadow")
        return LocalPlaylistRoomShadowImportResult(
            status = LocalPlaylistRoomShadowImportStatus.IMPORTED,
            playlistCount = validation.playlistCount,
            memberCount = validation.memberCount
        )
    }

    suspend fun importLegacyAndPromote(
        playlists: List<LocalPlaylist>,
        sourceDigest: String
    ): LocalPlaylistRoomShadowImportResult {
        val validation = mapper.validateRoundTrip(playlists)
        if (!validation.equivalent) {
            return LocalPlaylistRoomShadowImportResult(
                status = LocalPlaylistRoomShadowImportStatus.SKIPPED_NOT_EQUIVALENT,
                playlistCount = validation.playlistCount,
                memberCount = validation.memberCount,
                firstMismatch = validation.firstMismatch
            )
        }
        replacePlaylists(playlists, sourceDigest)
        return LocalPlaylistRoomShadowImportResult(
            status = LocalPlaylistRoomShadowImportStatus.IMPORTED,
            playlistCount = validation.playlistCount,
            memberCount = validation.memberCount
        )
    }

    companion object {
        const val SOURCE_DIGEST_METADATA_KEY = "local_playlist_source_digest"
        const val CUTOVER_STATE_METADATA_KEY = "local_playlist_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "local_playlist_import_schema"
        const val ROOM_PRIMARY_STATE = "room_primary"
        private const val MAX_PENDING_OUTBOX_ENTRIES = 256

        fun sourceDigest(playlists: List<LocalPlaylist>): String {
            return domainDigest(playlists)
        }

        fun domainDigest(playlists: List<LocalPlaylist>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val canonical = StringBuilder()
            fun append(value: Any?) {
                val text = value?.toString() ?: "<null>"
                canonical.append(text.length).append(':').append(text).append('|')
            }
            append(playlists.size)
            playlists.forEachIndexed { playlistIndex, playlist ->
                append(playlistIndex)
                append(playlist.id)
                append(playlist.name)
                append(playlist.modifiedAt)
                append(playlist.customCoverUrl)
                append(playlist.songOrderVersion)
                append(playlist.songs.size)
                playlist.songs.forEachIndexed { songIndex, song ->
                    append(songIndex)
                    append(song.identity().stableKey())
                    append(song.id)
                    append(song.name)
                    append(song.artist)
                    append(song.album)
                    append(song.albumId)
                    append(song.durationMs)
                    append(song.coverUrl)
                    append(song.mediaUri)
                    append(song.matchedLyric)
                    append(song.matchedTranslatedLyric)
                    append(song.matchedLyricSource)
                    append(song.matchedSongId)
                    append(song.userLyricOffsetMs)
                    append(song.customCoverUrl)
                    append(song.customName)
                    append(song.customArtist)
                    append(song.originalName)
                    append(song.originalArtist)
                    append(song.originalCoverUrl)
                    append(song.originalLyric)
                    append(song.originalTranslatedLyric)
                    append(song.localFileName)
                    append(song.localFilePath)
                    append(song.channelId)
                    append(song.audioId)
                    append(song.subAudioId)
                    append(song.playlistContextId)
                    append(song.sourceStableKey)
                    append(song.addedAt)
                    append(song.neteaseArtists?.size ?: 0)
                    song.neteaseArtists.orEmpty().forEach { artist ->
                        append(artist.id)
                            append(artist.name)
                        }
                    append(song.syncMembershipTokens?.size ?: 0)
                    song.syncMembershipTokens
                        .orEmpty()
                        .sortedWith(compareBy({ it.deviceId }, { it.counter }))
                        .forEach { token ->
                            append(token.deviceId)
                            append(token.counter)
                        }
                }
            }
            return digest.digest(canonical.toString().toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
        }

        private fun migrationMetadata(
            key: String,
            value: String,
            now: Long
        ) = moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity(
            key = key,
            value = value,
            updatedAt = now
        )
    }
}
