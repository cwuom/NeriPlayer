package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.LocalPlaylistDao
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistSummaryProjection
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore.Companion.CUTOVER_STATE_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore.Companion.IMPORT_SCHEMA_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore.Companion.ROOM_PRIMARY_STATE
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore.Companion.SOURCE_DIGEST_METADATA_KEY
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn

class LocalPlaylistRoomStoreIncrementalWriteTest {
    private val room = InlineTransactionDatabase()
    private val dao = InMemoryLocalPlaylistDao()
    private val store = LocalPlaylistRoomStore(room.database)

    init {
        doReturn(dao).`when`(room.database).localPlaylistDao()
    }

    @Test
    fun `incremental writes rewrite changed headers and members but keep identical track rows`() = runTest {
        val road = LocalPlaylist(id = 1, name = "Road", songs = mutableListOf(song(11, "A")), modifiedAt = 10)
        val gym = LocalPlaylist(id = 2, name = "Gym", songs = mutableListOf(song(12, "B")), modifiedAt = 10)
        store.replacePlaylists(listOf(road, gym), sourceDigest = "d0")
        dao.trackWrites.clear()
        val live = song(12, "B (live)")
        val renamed = road.copy(name = "Road trip")
        val relabelled = gym.copy(songs = mutableListOf(live))

        store.writeIncremental(listOf(road, gym), listOf(renamed, relabelled), sourceDigest = "d1", now = 20)

        assertEquals(listOf(listOf("B (live)")), dao.trackWrites)
        assertEquals(listOf("Road trip", "Gym"), dao.getPlaylists().map { it.name })

        val mix = LocalPlaylist(id = 3, name = "Mix", songs = mutableListOf(live), modifiedAt = 30)
        store.writeIncremental(listOf(renamed, relabelled), listOf(renamed, relabelled, mix), sourceDigest = "d2", now = 30)

        assertEquals(listOf(listOf("B (live)")), dao.trackWrites)
        assertEquals(listOf(renamed, relabelled, mix), store.readIfRoomPrimary())
        assertEquals(MigrationMetadataEntity(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, 30), room.metadata.rows[CUTOVER_STATE_METADATA_KEY])
        assertEquals(MigrationMetadataEntity(SOURCE_DIGEST_METADATA_KEY, "d2", 30), room.metadata.rows[SOURCE_DIGEST_METADATA_KEY])
        assertEquals(MigrationMetadataEntity(IMPORT_SCHEMA_METADATA_KEY, "1", 30), room.metadata.rows[IMPORT_SCHEMA_METADATA_KEY])
    }

    @Test
    fun `fast playlist reads report room authority and resolve members with their tokens`() = runTest {
        assertEquals(LocalPlaylistPreviewAuthority.Unmigrated, store.readFastPlaylistAuthority(1))
        assertNull(store.readPlaylistIfRoomPrimary(1))
        assertNull(store.readIfRoomPrimary())
        val tagged = song(11, "A").copy(syncMembershipTokens = listOf(SyncCausalToken("phone", 2), SyncCausalToken("phone", 1)))
        val road = LocalPlaylist(id = 1, name = "Road", songs = mutableListOf(tagged, song(12, "B")), modifiedAt = 10)
        val gym = LocalPlaylist(id = 2, name = "Gym", songs = mutableListOf(song(13, "C")), modifiedAt = 10)

        store.replacePlaylists(listOf(road, gym))

        assertEquals(LocalPlaylistPreviewAuthority.RoomPrimary(null), store.readFastPlaylistAuthority(99))
        val normalized = road.copy(
            songs = mutableListOf(
                tagged.copy(syncMembershipTokens = listOf(SyncCausalToken("phone", 1), SyncCausalToken("phone", 2))),
                song(12, "B")
            )
        )
        assertEquals(normalized, store.readPlaylistIfRoomPrimary(1))
        assertEquals(LocalPlaylistPreviewAuthority.RoomPrimary(gym), store.readFastPlaylistAuthority(2))
    }

    @Test
    fun `shadow imports skip an unchanged digest and refuse snapshots that do not round trip`() = runTest {
        val fine = LocalPlaylist(id = 1, name = "Road", songs = mutableListOf(song(11, "A")), modifiedAt = 10)
        val repeated = song(12, "B")
        val duplicates = LocalPlaylist(
            id = 5,
            name = "Dupes",
            songs = mutableListOf(
                repeated.copy(syncMembershipTokens = listOf(SyncCausalToken("phone", 1))),
                repeated.copy(syncMembershipTokens = listOf(SyncCausalToken("phone", 2)))
            ),
            modifiedAt = 10
        )

        val refused = store.importShadowSnapshotIfChanged(listOf(fine, duplicates), sourceDigest = "next")

        assertEquals(LocalPlaylistRoomShadowImportStatus.SKIPPED_NOT_EQUIVALENT, refused.status)
        assertEquals(2 to 3, refused.playlistCount to refused.memberCount)
        assertTrue(refused.firstMismatch.orEmpty().startsWith("playlist[1] expected=LocalPlaylist(id=5"))
        assertTrue(dao.getPlaylists().isEmpty() && room.metadata.rows.isEmpty())

        room.metadata.put(SOURCE_DIGEST_METADATA_KEY, "same")
        assertEquals(
            LocalPlaylistRoomShadowImportResult(LocalPlaylistRoomShadowImportStatus.SKIPPED_UNCHANGED, 2, 3),
            store.importShadowSnapshotIfChanged(listOf(fine, duplicates), sourceDigest = "same")
        )

        assertEquals(
            LocalPlaylistRoomShadowImportResult(LocalPlaylistRoomShadowImportStatus.IMPORTED, 1, 1),
            store.importShadowSnapshotIfChanged(listOf(fine), sourceDigest = "next")
        )
        assertEquals(listOf(fine), store.readPlaylists())
        assertEquals("next", room.metadata.value(SOURCE_DIGEST_METADATA_KEY))
        assertEquals(ROOM_PRIMARY_STATE, room.metadata.value(CUTOVER_STATE_METADATA_KEY))
    }

    @Test
    fun `undecodable member stays in room while the rest of the playlists load and keep writing`() = runTest {
        val road = LocalPlaylist(id = 1, name = "Road", songs = mutableListOf(song(11, "A"), song(12, "B")), modifiedAt = 10)
        val gym = LocalPlaylist(id = 2, name = "Gym", songs = mutableListOf(song(13, "C")), modifiedAt = 10)
        store.replacePlaylists(listOf(road, gym))
        val broken = dao.getMembersForPlaylist(1).single { it.memberPayloadJson.contains("\"name\":\"B\"") }
            .copy(memberPayloadJson = "{broken")
        dao.insertMembers(listOf(broken))
        dao.insertTracks(dao.getTracksByIdentityKeys(listOf(broken.identityKey)).map { it.copy(durablePayloadJson = "{broken") })

        val loaded = store.readIfRoomPrimary()!!
        assertEquals(listOf(listOf("A"), listOf("C")), loaded.map { playlist -> playlist.songs.map { it.name } })

        val renamed = loaded[0].copy(name = "Road trip", songs = (loaded[0].songs + song(14, "D")).toMutableList())
        store.writeIncremental(loaded, listOf(renamed, loaded[1]), sourceDigest = "d1", now = 20)

        assertEquals(listOf("A", "D"), store.readIfRoomPrimary()!![0].songs.map { it.name })
        assertEquals(broken, dao.getMembersForPlaylist(1).single { it.identityKey == broken.identityKey })
    }

    private fun song(id: Long, name: String) = SongItem(
        id = id,
        name = name,
        artist = "Artist",
        album = "Album",
        albumId = 5,
        durationMs = 180_000,
        coverUrl = "https://img.example/$id.jpg",
        mediaUri = "https://music.example/$id"
    )
}

/** Playlist tables keyed like their Room primary keys, including the member and token cascades. */
private class InMemoryLocalPlaylistDao : LocalPlaylistDao {
    private val playlists = linkedMapOf<Long, LocalPlaylistEntity>()
    private val tracks = linkedMapOf<String, TrackEntity>()
    private val members = linkedMapOf<Pair<Long, String>, PlaylistMemberEntity>()
    private val tokens = linkedMapOf<List<Any>, PlaylistMemberTokenEntity>()
    val trackWrites = mutableListOf<List<String>>()

    override fun observePlaylistSummaries(): Flow<List<LocalPlaylistSummaryProjection>> =
        throw UnsupportedOperationException("Summaries are not part of the store contract under test")

    override suspend fun countPlaylists() = playlists.size

    override suspend fun getPlaylists() = playlists.values.sortedWith(compareBy({ it.displayPosition }, { it.playlistId }))

    override suspend fun getPlaylist(playlistId: Long) = playlists[playlistId]

    override suspend fun getMembers() =
        members.values.sortedWith(compareBy({ it.playlistId }, { it.displayPosition }, { it.identityKey }))

    override suspend fun getMembersForPlaylist(playlistId: Long) =
        members.values.filter { it.playlistId == playlistId }.sortedWith(compareBy({ it.displayPosition }, { it.identityKey }))

    override suspend fun getMemberTokens() = tokens.values.sortedWith(
        compareBy({ it.playlistId }, { it.identityKey }, { it.tokenIndex }, { it.deviceId }, { it.counter })
    )

    override suspend fun getMemberTokensForPlaylist(playlistId: Long) = tokens.values.filter { it.playlistId == playlistId }
        .sortedWith(compareBy({ it.identityKey }, { it.tokenIndex }, { it.deviceId }, { it.counter }))

    override suspend fun getTracks() = tracks.values.sortedBy { it.identityKey }

    override suspend fun getTracksByIdentityKeys(identityKeys: List<String>) = identityKeys.mapNotNull { tracks[it] }

    override suspend fun insertPlaylists(playlists: List<LocalPlaylistEntity>) {
        playlists.forEach { this.playlists[it.playlistId] = it }
    }

    override suspend fun insertTracks(tracks: List<TrackEntity>) {
        trackWrites.add(tracks.map { it.name })
        tracks.forEach { this.tracks[it.identityKey] = it }
    }

    override suspend fun insertMembers(members: List<PlaylistMemberEntity>) {
        members.forEach { this.members[it.playlistId to it.identityKey] = it }
    }

    override suspend fun insertMemberTokens(tokens: List<PlaylistMemberTokenEntity>) {
        tokens.forEach { this.tokens[tokenKey(it)] = it }
    }

    override suspend fun deleteMemberTokenRows(tokens: List<PlaylistMemberTokenEntity>) {
        tokens.forEach { this.tokens.remove(tokenKey(it)) }
    }

    override suspend fun deleteMembersByIdentityKeys(playlistId: Long, identityKeys: List<String>) =
        removeMembers { it.playlistId == playlistId && it.identityKey in identityKeys }

    override suspend fun updateMemberPosition(playlistId: Long, identityKey: String, displayPosition: Int) {
        val member = members[playlistId to identityKey] ?: return
        members[playlistId to identityKey] = member.copy(displayPosition = displayPosition, orderTieBreak = displayPosition)
    }

    override suspend fun deleteOrphanTracksByIdentityKeys(identityKeys: List<String>) {
        identityKeys.filter { key -> members.values.none { it.identityKey == key } }.forEach { tracks.remove(it) }
    }

    override suspend fun deleteMemberTokens() = tokens.clear()

    override suspend fun deleteMemberTokensForPlaylist(playlistId: Long) {
        tokens.values.removeAll { it.playlistId == playlistId }
    }

    override suspend fun deleteMembers() = removeMembers { true }

    override suspend fun deleteMembersForPlaylist(playlistId: Long) = removeMembers { it.playlistId == playlistId }

    override suspend fun deletePlaylists() {
        playlists.clear()
        removeMembers { true }
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlists.remove(playlistId)
        removeMembers { it.playlistId == playlistId }
    }

    override suspend fun deleteTracks() {
        tracks.clear()
        removeMembers { true }
    }

    override suspend fun deleteOrphanTracks() {
        tracks.keys.removeAll { key -> members.values.none { it.identityKey == key } }
    }

    private fun removeMembers(predicate: (PlaylistMemberEntity) -> Boolean) {
        val removed = members.values.filter(predicate).map { it.playlistId to it.identityKey }.toSet()
        members.keys.removeAll(removed)
        tokens.values.removeAll { (it.playlistId to it.identityKey) in removed }
    }

    private fun tokenKey(token: PlaylistMemberTokenEntity) =
        listOf(token.playlistId, token.identityKey, token.deviceId, token.counter)
}
