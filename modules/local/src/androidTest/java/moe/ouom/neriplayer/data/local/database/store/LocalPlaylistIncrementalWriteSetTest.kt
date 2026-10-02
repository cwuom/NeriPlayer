package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.LocalPlaylistEntity
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.PlaylistMemberTokenEntity
import moe.ouom.neriplayer.data.local.database.entity.TrackEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPlaylistIncrementalWriteSetTest {
    @Test
    fun samePositionMetadataEditWritesOnlyTheChangedMemberAndTrack() = runTest {
        withStore {
            val songs = (1L..3L).map(::song)
            val initial = listOf(playlist(10L, songs))
            install(initial)
            val previousTokens = database.localPlaylistDao().getMemberTokens()
            val changed = songs[1].copy(name = "edited title", customArtist = "edited custom artist")
            val next = listOf(initial.single().copy(
                songs = mutableListOf(songs[0], changed, songs[2]), modifiedAt = 400L
            ))
            rejectWrites("playlist_member", changed.stableKey(), playlistId = 10L)
            rejectWrites("track", changed.stableKey())
            rejectWrites("playlist_member_token")

            write(initial, next)

            assertEquals(next, store.readIfRoomPrimary())
            assertEquals(previousTokens, database.localPlaylistDao().getMemberTokens())
            assertEquals(3, database.localPlaylistDao().getMembers().size)
            assertEquals(3, database.localPlaylistDao().getTracks().size)
        }
    }

    @Test
    fun headerOnlyChangeAndPlaylistReorderDoNotWriteMembersTokensOrTracks() = runTest {
        withStore {
            val initial = listOf(playlist(10L, listOf(song(1L), song(2L))), playlist(20L, listOf(song(3L))))
            install(initial)
            val before = state()
            val next = listOf(initial[1], initial[0].copy(
                name = "renamed", customCoverUrl = "https://cover.example/renamed.jpg", modifiedAt = 400L
            ))
            rejectWrites("playlist_member")
            rejectWrites("playlist_member_token")
            rejectWrites("track")

            write(initial, next)

            assertEquals(next, store.readIfRoomPrimary())
            val after = state()
            assertEquals(before.members, after.members)
            assertEquals(before.tokens, after.tokens)
            assertEquals(before.tracks, after.tracks)
        }
    }

    @Test
    fun sharedIdentityEditPreservesAnotherPlaylistsCustomMetadataAndLyrics() = runTest {
        withStore {
            val first = song(1L).copy(customName = "first title", matchedLyric = "first cached lyrics",
                playlistContextId = "first-context")
            val second = first.copy(customName = "second title", matchedLyric = "second edited lyrics",
                matchedTranslatedLyric = "second translation", matchedRomanizedLyric = "second romanization",
                originalLyric = "second original", originalRomanizedLyric = "second original romanization",
                lyricSyncEdited = true, lyricSyncRevision = 13L, playlistContextId = "second-context",
                syncMembershipTokens = listOf(SyncCausalToken("second-device", 5L)))
            assertEquals(first.stableKey(), second.stableKey())
            val initial = listOf(playlist(10L, listOf(first)), playlist(20L, listOf(second)))
            install(initial)
            val before = state()
            val changed = first.copy(customName = "edited first title", matchedLyric = "new first edited lyrics",
                matchedRomanizedLyric = "new first romanization", lyricSyncEdited = true, lyricSyncRevision = 20L)
            val next = listOf(initial[0].copy(songs = mutableListOf(changed), modifiedAt = 400L), initial[1])
            rejectWrites("playlist_member", first.stableKey(), playlistId = 10L)
            rejectWrites("track", first.stableKey())
            rejectWrites("playlist_member_token")

            write(initial, next)

            val restored = checkNotNull(store.readIfRoomPrimary())
            assertEquals(next, restored)
            assertEquals(second, restored.single { it.id == 20L }.songs.single())
            assertEquals(before.members.single { it.playlistId == 20L },
                database.localPlaylistDao().getMembers().single { it.playlistId == 20L })
            assertEquals(before.tokens, database.localPlaylistDao().getMemberTokens())
        }
    }

    @Test
    fun positionOnlyInputPreservesMemberPayloadsTracksAndTokens() = runTest {
        withStore {
            val songs = (1L..3L).map(::song)
            val initial = listOf(playlist(10L, songs))
            install(initial)
            val before = state()
            val next = listOf(initial.single().copy(songs = mutableListOf(songs[2], songs[0], songs[1])))
            rejectWrites("track")
            rejectWrites("playlist_member_token")
            rejectOperation("playlist_member", "INSERT")
            rejectOperation("playlist_member", "DELETE")
            rejectOperation("playlist_member", "UPDATE",
                "NEW.member_payload_json IS NOT OLD.member_payload_json OR " +
                    "NEW.member_payload_schema_version IS NOT OLD.member_payload_schema_version OR " +
                    "NEW.added_at IS NOT OLD.added_at OR " +
                    "NEW.playlist_context_id IS NOT OLD.playlist_context_id")

            write(initial, next)

            assertEquals(next, store.readIfRoomPrimary())
            val after = state()
            assertEquals(before.tracks, after.tracks)
            assertEquals(before.tokens, after.tokens)
            val previousByIdentity = before.members.associateBy { it.identityKey }
            after.members.forEach { member ->
                assertEquals(checkNotNull(previousByIdentity[member.identityKey]).copy(
                    displayPosition = member.displayPosition, orderTieBreak = member.orderTieBreak
                ), member)
            }
            assertEquals(next.single().songs.map(SongItem::stableKey), after.members.map { it.identityKey })
            assertEquals(listOf(0, 1, 2), after.members.map { it.displayPosition })
        }
    }

    @Test
    fun failureAfterMemberWritesRollsBackEveryTableAndPrimaryMetadata() = runTest {
        withStore {
            val songs = listOf(song(1L), song(2L))
            val initial = listOf(playlist(10L, songs))
            install(initial)
            val before = state()
            val next = listOf(initial.single().copy(name = "new header", modifiedAt = 400L,
                songs = mutableListOf(songs[0].copy(name = "edited-first"), songs[1].copy(name = "edited-second"))))
            val sqlite = database.openHelper.writableDatabase
            listOf("INSERT", "UPDATE").forEach { operation ->
                sqlite.execSQL(
                    "CREATE TRIGGER reject_final_digest_${operation.lowercase()} BEFORE $operation ON migration_metadata " +
                        "WHEN NEW.key = 'local_playlist_source_digest' AND EXISTS " +
                        "(SELECT 1 FROM playlist_member WHERE playlist_id = 10 " +
                        "AND member_payload_json LIKE '%edited-first%') " +
                        "BEGIN SELECT RAISE(ABORT, 'Injected failure after member write'); END"
                )
            }

            val failure = runCatching { write(initial, next) }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(generateSequence(checkNotNull(failure)) { it.cause }
                .any { it.message.orEmpty().contains("Injected failure after member write") })
            assertEquals(before, state())
            assertEquals(initial, LocalPlaylistRoomStore(database).readIfRoomPrimary())
            sqlite.execSQL("DROP TRIGGER reject_final_digest_insert")
            sqlite.execSQL("DROP TRIGGER reject_final_digest_update")
            write(initial, next)
            assertEquals(next, LocalPlaylistRoomStore(database).readIfRoomPrimary())
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

        fun rejectWrites(table: String, allowedIdentity: String? = null, playlistId: Long? = null) {
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val row = if (operation == "INSERT") "NEW" else "OLD"
                val condition = allowedIdentity?.let { identity ->
                    val matchingIdentity = "$row.identity_key = '${identity.replace("'", "''")}'"
                    val matchingPlaylist = playlistId?.let { " AND $row.playlist_id = $it" }.orEmpty()
                    "NOT ($matchingIdentity$matchingPlaylist)"
                }
                rejectOperation(table, operation, condition)
            }
        }

        fun rejectOperation(table: String, operation: String, condition: String? = null) {
            val whenClause = condition?.let { " WHEN $it" }.orEmpty()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_${table}_${operation.lowercase()} BEFORE $operation ON $table$whenClause " +
                    "BEGIN SELECT RAISE(ABORT, 'Unexpected $table $operation'); END"
            )
        }

        suspend fun state(): DurableState {
            val dao = database.localPlaylistDao()
            val keys = listOf(LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY,
                LocalPlaylistRoomStore.SOURCE_DIGEST_METADATA_KEY, LocalPlaylistRoomStore.IMPORT_SCHEMA_METADATA_KEY)
            return DurableState(dao.getPlaylists(), dao.getTracks(), dao.getMembers(), dao.getMemberTokens(),
                keys.map { database.syncMetadataDao().getMigrationMetadata(it) })
        }
    }

    private data class DurableState(
        val playlists: List<LocalPlaylistEntity>,
        val tracks: List<TrackEntity>,
        val members: List<PlaylistMemberEntity>,
        val tokens: List<PlaylistMemberTokenEntity>,
        val metadata: List<MigrationMetadataEntity?>
    )

    private fun playlist(id: Long, songs: List<SongItem>) = LocalPlaylist(
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
