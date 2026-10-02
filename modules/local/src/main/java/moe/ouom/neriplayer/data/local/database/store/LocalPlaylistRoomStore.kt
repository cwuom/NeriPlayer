package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.database.store.stats.toDomain

import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.stableKey

import androidx.room.withTransaction
import com.google.gson.Gson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistDao
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxStatus
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutationOutbox
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutation
import moe.ouom.neriplayer.data.local.playlist.decodeLocalPlaylistSyncMutation
import moe.ouom.neriplayer.data.model.stableKey
import java.security.MessageDigest
import java.io.IOException
import java.util.UUID

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

internal sealed interface LocalPlaylistPreviewAuthority {
    data object Unmigrated : LocalPlaylistPreviewAuthority
    data class RoomPrimary(val playlist: LocalPlaylist?) : LocalPlaylistPreviewAuthority
}

internal class LocalPlaylistRoomStore(
    private val database: NeriUserDataDatabase,
    private val gson: Gson = Gson()
) {
    private val mapper = LocalPlaylistRoomMapper(gson)

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

    suspend fun markLegacyJsonPrimary(
        sourceDigest: String,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            database.syncMetadataDao().upsertMigrationMetadata(
                migrationMetadata(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, now)
            )
            database.syncMetadataDao().upsertMigrationMetadata(
                migrationMetadata(SOURCE_DIGEST_METADATA_KEY, sourceDigest, now)
            )
        }
    }

    suspend fun replacePlaylists(
        playlists: List<LocalPlaylist>,
        sourceDigest: String? = null
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
                memberTokens = snapshot.memberTokens
            )
            snapshot.migrationMetadata
                .map { metadata ->
                    if (metadata.key == CUTOVER_STATE_METADATA_KEY) {
                        metadata.copy(value = ROOM_PRIMARY_STATE)
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
        val changes = mapper.toWriteSet(previous, next)
        database.withTransaction {
            applyWriteSet(changes)
            database.syncMetadataDao().upsertMigrationMetadata(
                migrationMetadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
            )
            database.syncMetadataDao().upsertMigrationMetadata(
                migrationMetadata(SOURCE_DIGEST_METADATA_KEY, sourceDigest, now)
            )
            if (changes.domainChanged) {
                database.syncMetadataDao().upsertMigrationMetadata(
                    migrationMetadata(
                        IMPORT_SCHEMA_METADATA_KEY,
                        moe.ouom.neriplayer.data.local.database.entity.LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION.toString(),
                        now
                    )
                )
            }
        }
    }

    private suspend fun applyWriteSet(changes: LocalPlaylistRoomWriteSet) {
        val dao = database.localPlaylistDao()
        changes.removedPlaylistIds.forEach { dao.deletePlaylist(it) }
        changes.removedMembers.forEach { (playlistId, keys) ->
            keys.chunked(PLAYLIST_WRITE_BATCH_SIZE).forEach { dao.deleteMembersByIdentityKeys(playlistId, it) }
        }
        if (changes.playlists.isNotEmpty()) dao.insertPlaylists(changes.playlists)
        writeChangedTracks(dao, changes)
        changes.members.chunked(PLAYLIST_WRITE_BATCH_SIZE).forEach { dao.insertMembers(it) }
        changes.positions.forEach { dao.updateMemberPosition(it.playlistId, it.identityKey, it.displayPosition) }
        changes.removedTokens.chunked(PLAYLIST_WRITE_BATCH_SIZE).forEach { dao.deleteMemberTokenRows(it) }
        changes.memberTokens.chunked(PLAYLIST_WRITE_BATCH_SIZE).forEach { dao.insertMemberTokens(it) }
        changes.orphanCandidates.toList().chunked(PLAYLIST_WRITE_BATCH_SIZE).forEach {
            dao.deleteOrphanTracksByIdentityKeys(it)
        }
    }

    private suspend fun writeChangedTracks(dao: LocalPlaylistDao, changes: LocalPlaylistRoomWriteSet) {
        changes.tracks.chunked(PLAYLIST_WRITE_BATCH_SIZE).forEach { candidates ->
            val existing = dao.getTracksByIdentityKeys(candidates.map { it.identityKey }).associateBy { it.identityKey }
            val changed = candidates.filter { existing[it.identityKey] != it }
            if (changed.isNotEmpty()) dao.insertTracks(changed)
        }
    }

    suspend fun readPlaylists(): List<LocalPlaylist> {
        return database.withTransaction {
            mapper.toDomain(
                playlists = database.localPlaylistDao().getPlaylists(),
                tracks = database.localPlaylistDao().getTracks(),
                members = database.localPlaylistDao().getMembers(),
                memberTokens = database.localPlaylistDao().getMemberTokens()
            )
        }
    }

    suspend fun readPlaylistIfRoomPrimary(playlistId: Long): LocalPlaylist? {
        return when (val authority = readFastPlaylistAuthority(playlistId)) {
            LocalPlaylistPreviewAuthority.Unmigrated -> null
            is LocalPlaylistPreviewAuthority.RoomPrimary -> authority.playlist
        }
    }

    suspend fun readFastPlaylistAuthority(playlistId: Long): LocalPlaylistPreviewAuthority {
        return database.withTransaction {
            if (!isRoomPrimary()) return@withTransaction LocalPlaylistPreviewAuthority.Unmigrated
            val dao = database.localPlaylistDao()
            val playlist = dao.getPlaylist(playlistId)
                ?: return@withTransaction LocalPlaylistPreviewAuthority.RoomPrimary(null)
            val members = dao.getMembersForPlaylist(playlistId)
            val identityKeys = members.mapTo(linkedSetOf()) { it.identityKey }
            val tracks = identityKeys
                .chunked(500)
                .flatMap { keys ->
                    if (keys.isEmpty()) emptyList() else dao.getTracksByIdentityKeys(keys)
                }
            val resolved = mapper.toDomain(
                playlists = listOf(playlist),
                tracks = tracks,
                members = members,
                memberTokens = dao.getMemberTokensForPlaylist(playlistId)
            ).firstOrNull()
            LocalPlaylistPreviewAuthority.RoomPrimary(resolved)
        }
    }

    suspend fun readPendingSyncMutationOutbox(): LocalPlaylistSyncMutationOutbox? {
        return database.withTransaction {
            val mutations = ArrayList<LocalPlaylistSyncMutation>()
            var afterSequence = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val entries = database.syncMetadataDao().getOutboxPage(
                    statuses = listOf(SyncOutboxStatus.PENDING),
                    afterSequence = afterSequence,
                    limit = PENDING_OUTBOX_PAGE_SIZE
                )
                if (entries.isEmpty()) break
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    if (entry.payloadVersion != 1) throw IOException("Unsupported playlist sync outbox payload version")
                    mutations.add(decodeLocalPlaylistSyncMutation(entry.mutationPayloadJson))
                }
                afterSequence = entries.last().sequence
            }
            currentCoroutineContext().ensureActive()
            if (mutations.isEmpty()) null else LocalPlaylistSyncMutationOutbox(mutations)
        }
    }

    suspend fun writePendingSyncMutationOutbox(
        outbox: LocalPlaylistSyncMutationOutbox,
        now: Long = System.currentTimeMillis()
    ) {
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

        replacePlaylists(playlists, sourceDigest)
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
        const val LEGACY_JSON_STATE = "legacy_json"
        private const val PLAYLIST_WRITE_BATCH_SIZE = 500
        private const val PENDING_OUTBOX_PAGE_SIZE = 256

        fun sourceDigest(playlists: List<LocalPlaylist>): String {
            return domainDigest(playlists)
        }

        fun domainDigest(playlists: List<LocalPlaylist>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun append(value: Any?) {
                val text = value?.toString() ?: "<null>"
                // 直接写入摘要，避免大曲库先拼接一份完整的规范化字符串
                digest.update(text.length.toString().toByteArray(Charsets.UTF_8))
                digest.update(COLON_BYTE)
                digest.update(text.toByteArray(Charsets.UTF_8))
                digest.update(PIPE_BYTE)
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
                    // 默认值保留旧摘要，避免升级后丢失已提交的旧 outbox
                    if (song.lyricSyncRevision != 0L) {
                        append("lyricSyncRevision")
                        append(song.lyricSyncRevision)
                    }
                    if (song.lyricSyncEdited != null) {
                        append("lyricSyncEdited")
                        append(song.lyricSyncEdited)
                    }
                }
            }
            return digest.digest()
                .joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
        }

        private val COLON_BYTE = byteArrayOf(':'.code.toByte())
        private val PIPE_BYTE = byteArrayOf('|'.code.toByte())

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
