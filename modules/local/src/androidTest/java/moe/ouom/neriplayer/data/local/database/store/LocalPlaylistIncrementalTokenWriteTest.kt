package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistIncrementalTokenWriteTest {
    @Test
    fun tokenDifferenceUsesTheCompleteMemberDeviceAndCounterKey() = runTest {
        withStore {
            val originalTokens = listOf(SyncCausalToken("a", 1L), SyncCausalToken("a", 2L), SyncCausalToken("c", 1L))
            val shared = song(1L).copy(syncMembershipTokens = originalTokens)
            val other = song(2L).copy(syncMembershipTokens = originalTokens)
            val secondCopy = shared.copy(customName = "other playlist title", playlistContextId = "other-context")
            val initial = listOf(playlist(10L, shared, other), playlist(20L, secondCopy))
            install(initial)
            val dao = database.localPlaylistDao()
            val before = dao.getMemberTokens()
            val identity = shared.stableKey()
            val removed = PlaylistMemberTokenEntity(10L, identity, "a", 1L, 0)
            val shiftedBefore = PlaylistMemberTokenEntity(10L, identity, "a", 2L, 1)
            val shiftedAfter = shiftedBefore.copy(tokenIndex = 0)
            val inserted = PlaylistMemberTokenEntity(10L, identity, "b", 4L, 1)
            val retained = PlaylistMemberTokenEntity(10L, identity, "c", 1L, 2)
            allowTokenWrite("DELETE", matchesToken("OLD", removed))
            allowTokenWrite("INSERT", "(${matchesToken("NEW", shiftedAfter)}) OR (${matchesToken("NEW", inserted)})")
            allowTokenWrite("UPDATE", "(${matchesToken("OLD", shiftedBefore)}) AND (${matchesToken("NEW", shiftedAfter)})")
            val nextTokens = listOf(SyncCausalToken("a", 2L), SyncCausalToken("b", 4L), SyncCausalToken("c", 1L))
            val next = listOf(initial[0].copy(
                songs = mutableListOf(shared.copy(syncMembershipTokens = nextTokens), other), modifiedAt = 400L
            ), initial[1])

            write(initial, next)

            val after = dao.getMemberTokens()
            assertEquals(next, store.readIfRoomPrimary())
            assertEquals(listOf(shiftedAfter, inserted, retained), after.filter { it.playlistId == 10L && it.identityKey == identity })
            assertEquals(before.filterNot { it.playlistId == 10L && it.identityKey == identity },
                after.filterNot { it.playlistId == 10L && it.identityKey == identity })
            assertEquals(before.single { it == retained }, after.single { it == retained })
            assertFalse(after.contains(removed))
            assertEquals(before.size, after.size)
        }
    }

    @Test
    fun memberAndPlaylistDeletionKeepSharedTracksUntilTheLastReferenceIsRemoved() = runTest {
        withStore {
            val shared = song(1L)
            val unique = song(2L)
            val secondCopy = shared.copy(customName = "second title", matchedLyric = "second edited lyric",
                lyricSyncEdited = true, lyricSyncRevision = 21L, playlistContextId = "second-context")
            val thirdCopy = shared.copy(customName = "third title", matchedRomanizedLyric = "third romanization",
                originalRomanizedLyric = "third original romanization", playlistContextId = "third-context")
            val initial = listOf(playlist(10L, shared, unique), playlist(20L, secondCopy), playlist(30L, thirdCopy))
            install(initial)
            val dao = database.localPlaylistDao()
            val initialTracks = dao.getTracks()
            val initialTokens = dao.getMemberTokens()
            rejectOperation("track", "INSERT")
            rejectOperation("track", "UPDATE")

            val withoutFirstMember = listOf(initial[0].copy(songs = mutableListOf(unique), modifiedAt = 400L), initial[1], initial[2])
            write(initial, withoutFirstMember)

            assertEquals(withoutFirstMember, store.readIfRoomPrimary())
            assertEquals(initialTracks, dao.getTracks())
            assertFalse(dao.getMembers().any { it.playlistId == 10L && it.identityKey == shared.stableKey() })
            assertEquals(initialTokens.filterNot { it.playlistId == 10L && it.identityKey == shared.stableKey() }, dao.getMemberTokens())

            val withoutSecondPlaylist = listOf(withoutFirstMember[0], initial[2])
            write(withoutFirstMember, withoutSecondPlaylist)

            assertEquals(withoutSecondPlaylist, store.readIfRoomPrimary())
            assertEquals(initialTracks, dao.getTracks())
            assertFalse(dao.getMembers().any { it.playlistId == 20L })
            assertEquals(initialTokens.filter { it.playlistId == 30L }, dao.getMemberTokens().filter { it.playlistId == 30L })
            assertFalse(dao.getMemberTokens().any { it.playlistId == 20L })

            val withoutLastSharedReference = listOf(withoutSecondPlaylist[0])
            write(withoutSecondPlaylist, withoutLastSharedReference)

            assertEquals(withoutLastSharedReference, store.readIfRoomPrimary())
            assertEquals(listOf(unique.stableKey()), dao.getTracks().map { it.identityKey })
            assertEquals(initialTracks.single { it.identityKey == unique.stableKey() }, dao.getTracks().single())
            assertEquals(listOf(unique.stableKey()), dao.getMembers().map { it.identityKey })
            assertEquals(initialTokens.filter { it.playlistId == 10L && it.identityKey == unique.stableKey() }, dao.getMemberTokens())

            write(withoutLastSharedReference, emptyList())

            assertEquals(emptyList<LocalPlaylist>(), store.readIfRoomPrimary())
            assertTrue(dao.getPlaylists().isEmpty())
            assertTrue(dao.getMembers().isEmpty())
            assertTrue(dao.getMemberTokens().isEmpty())
            assertTrue(dao.getTracks().isEmpty())
        }
    }

    @Test
    fun newPlaylistAndMembersKeepCompletePayloadAndNormalizedTokenIndexes() = runTest {
        withStore {
            val existing = song(1L)
            val initial = listOf(playlist(10L, existing))
            install(initial)
            val dao = database.localPlaylistDao()
            val originalTokens = dao.getMemberTokens()
            val originalMember = dao.getMembers().single()
            val originalTrack = dao.getTracks().single()
            val added = song(2L).copy(playlistContextId = "added-context", localFileName = "offline.flac",
                localFilePath = "/Music/offline.flac", sourceStableKey = "offline-source", lyricSyncEdited = true,
                lyricSyncRevision = 22L)
            val rawTokens = listOf(SyncCausalToken("z", 6L), SyncCausalToken("a", 2L),
                SyncCausalToken("a", 1L), SyncCausalToken("a", 1L))
            val newPlaylistSong = song(3L).copy(syncMembershipTokens = rawTokens, playlistContextId = "new-context",
                customName = "new custom title", matchedLyric = "new edited original", matchedTranslatedLyric = "new edited translation",
                matchedRomanizedLyric = "new edited romanization", lyricSyncEdited = true, lyricSyncRevision = 30L,
                streamUrl = "https://temporary-stream.example")
            val next = listOf(initial[0].copy(songs = mutableListOf(existing, added), modifiedAt = 400L),
                playlist(20L, newPlaylistSong))
            val allowedNewTokens = listOf(
                PlaylistMemberTokenEntity(10L, added.stableKey(), "actor-a", 2L, 0),
                PlaylistMemberTokenEntity(10L, added.stableKey(), "actor-z", 2L, 1),
                PlaylistMemberTokenEntity(20L, newPlaylistSong.stableKey(), "a", 1L, 0),
                PlaylistMemberTokenEntity(20L, newPlaylistSong.stableKey(), "a", 2L, 1),
                PlaylistMemberTokenEntity(20L, newPlaylistSong.stableKey(), "z", 6L, 2)
            )
            allowTokenWrite("INSERT", allowedNewTokens.joinToString(" OR ") { "(${matchesToken("NEW", it)})" })
            rejectOperation("playlist_member_token", "UPDATE")
            rejectOperation("playlist_member_token", "DELETE")

            write(initial, next)

            val normalizedNewSong = newPlaylistSong.copy(streamUrl = null,
                syncMembershipTokens = listOf(SyncCausalToken("a", 1L), SyncCausalToken("a", 2L), SyncCausalToken("z", 6L)))
            assertEquals(listOf(next[0], next[1].copy(songs = mutableListOf(normalizedNewSong))), store.readIfRoomPrimary())
            assertEquals(originalTokens, dao.getMemberTokens().filter { it.identityKey == existing.stableKey() })
            assertEquals(originalMember, dao.getMembers().single { it.identityKey == existing.stableKey() })
            assertEquals(originalTrack, dao.getTracks().single { it.identityKey == existing.stableKey() })
            assertEquals(allowedNewTokens, dao.getMemberTokens().filterNot { it.identityKey == existing.stableKey() })
            assertEquals(3, dao.getTracks().size)
            val persistedNewPayload = dao.getMembers().single { it.playlistId == 20L }.memberPayloadJson
            assertEquals(newPlaylistSong.copy(streamUrl = null), Gson().fromJson(persistedNewPayload, SongItem::class.java))
            val persistedTrackPayload = dao.getTracks().single { it.identityKey == newPlaylistSong.stableKey() }.durablePayloadJson
            assertEquals(newPlaylistSong.copy(streamUrl = null), Gson().fromJson(persistedTrackPayload, SongItem::class.java))
        }
    }

    private suspend fun withStore(block: suspend Fixture.() -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries().build()
        try { Fixture(database).block() } finally { database.close() }
    }

    private class Fixture(val database: NeriUserDataDatabase) {
        val store = LocalPlaylistRoomStore(database)

        suspend fun install(playlists: List<LocalPlaylist>) {
            store.replacePlaylists(playlists, LocalPlaylistRoomStore.domainDigest(playlists))
        }

        suspend fun write(previous: List<LocalPlaylist>, next: List<LocalPlaylist>) {
            store.writeIncremental(previous, next, LocalPlaylistRoomStore.domainDigest(next), now = 500L)
        }

        fun allowTokenWrite(operation: String, condition: String) {
            rejectOperation("playlist_member_token", operation, "NOT ($condition)")
        }

        fun matchesToken(row: String, token: PlaylistMemberTokenEntity): String {
            val identity = token.identityKey.replace("'", "''")
            val device = token.deviceId.replace("'", "''")
            return "$row.playlist_id = ${token.playlistId} AND $row.identity_key = '$identity' AND " +
                "$row.device_id = '$device' AND $row.counter = ${token.counter} AND $row.token_index = ${token.tokenIndex}"
        }

        fun rejectOperation(table: String, operation: String, condition: String? = null) {
            val whenClause = condition?.let { " WHEN $it" }.orEmpty()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_${table}_${operation.lowercase()} BEFORE $operation ON $table$whenClause " +
                    "BEGIN SELECT RAISE(ABORT, 'Unexpected $table $operation'); END"
            )
        }
    }

    private fun playlist(id: Long, vararg songs: SongItem) = LocalPlaylist(
        id = id, name = "playlist-$id", songs = songs.toMutableList(), modifiedAt = 300L,
        customCoverUrl = "https://cover.example/playlist-$id.jpg"
    )

    private fun song(id: Long) = SongItem(
        id = id, name = "song-$id", artist = "artist-$id", album = "netease", albumId = id + 10L,
        durationMs = 180_000L, coverUrl = "https://cover.example/$id.jpg", addedAt = 100L - id,
        customName = "custom-$id", originalName = "original-$id", matchedLyric = "cached-$id",
        matchedTranslatedLyric = "translation-$id", matchedRomanizedLyric = "romanization-$id",
        originalLyric = "original-lyrics-$id", originalTranslatedLyric = "original-translation-$id",
        originalRomanizedLyric = "original-romanization-$id", userLyricOffsetMs = 25L,
        logicalCreatedAtMs = 50L, createdAtSource = "metadata", createdAtConfidence = "exact",
        membershipAddedAtMs = 90L, sourceModifiedAtMs = 45L, lyricSyncEdited = false,
        syncMembershipTokens = listOf(SyncCausalToken("actor-a", id), SyncCausalToken("actor-z", id))
    )
}
