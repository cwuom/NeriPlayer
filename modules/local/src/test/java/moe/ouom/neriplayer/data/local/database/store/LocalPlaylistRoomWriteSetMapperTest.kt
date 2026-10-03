package moe.ouom.neriplayer.data.local.database.store

import com.google.gson.Gson
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistRoomWriteSetMapperTest {
    private val mapper = LocalPlaylistRoomMapper()

    @Test
    fun `temporary stream refresh does not change durable members tracks or tokens`() {
        val song = song(1L).copy(streamUrl = "https://stream.example/old")
        val initial = playlist(10L, song)
        val next = initial.copy(songs = mutableListOf(song.copy(streamUrl = "https://stream.example/new")))

        val changes = mapper.toWriteSet(listOf(initial), listOf(next))

        assertTrue(changes.domainChanged)
        assertTrue(changes.playlists.isEmpty())
        assertTrue(changes.members.isEmpty())
        assertTrue(changes.positions.isEmpty())
        assertTrue(changes.tracks.isEmpty())
        assertTrue(changes.removedTokens.isEmpty())
        assertTrue(changes.memberTokens.isEmpty())
        assertTrue(changes.orphanCandidates.isEmpty())
    }

    @Test
    fun `unchanged domain produces no write candidates`() {
        val initial = listOf(playlist(10L, song(1L)))

        val changes = mapper.toWriteSet(initial, initial)

        assertFalse(changes.domainChanged)
        assertTrue(changes.playlists.isEmpty())
        assertTrue(changes.members.isEmpty())
        assertTrue(changes.removedPlaylistIds.isEmpty())
        assertTrue(changes.removedMembers.isEmpty())
        assertTrue(changes.tracks.isEmpty())
    }

    @Test
    fun `new playlist keeps its global position membership context and complete causal tokens`() {
        val existing = playlist(10L, song(1L))
        val added = song(2L).copy(playlistContextId = "new-context")
        val newPlaylist = playlist(20L, added)

        val changes = mapper.toWriteSet(listOf(existing), listOf(existing, newPlaylist))

        assertEquals(20L, changes.playlists.single().playlistId)
        assertEquals(1, changes.playlists.single().displayPosition)
        assertEquals("new-context", changes.members.single().playlistContextId)
        assertEquals(added, decode(changes.members.single().memberPayloadJson))
        assertEquals(added, decode(changes.tracks.single().durablePayloadJson))
        assertEquals(listOf("actor-a" to 2L, "actor-z" to 2L), changes.memberTokens.map { it.deviceId to it.counter })
        assertTrue(changes.memberTokens.all { it.playlistId == 20L && it.identityKey == added.stableKey() })
        assertTrue(changes.removedPlaylistIds.isEmpty())
    }

    @Test
    fun `moving unchanged songs keeps their durable payload and causal tokens untouched`() {
        val first = song(1L)
        val second = song(2L)
        val initial = playlist(10L, first, second)
        val next = initial.copy(songs = mutableListOf(second, first))

        val changes = mapper.toWriteSet(listOf(initial), listOf(next))

        assertTrue(changes.playlists.isEmpty())
        assertTrue(changes.members.isEmpty())
        assertTrue(changes.tracks.isEmpty())
        assertTrue(changes.removedTokens.isEmpty())
        assertTrue(changes.memberTokens.isEmpty())
        assertEquals(listOf(second.stableKey() to 0, first.stableKey() to 1),
            changes.positions.map { it.identityKey to it.displayPosition })
    }

    @Test
    fun `token removal keeps other actors and reindexes only the shifted survivor`() {
        val tokens = listOf(SyncCausalToken("a", 1L), SyncCausalToken("b", 2L), SyncCausalToken("c", 3L))
        val original = song(1L).copy(syncMembershipTokens = tokens)
        val initial = playlist(10L, original)
        val next = initial.copy(songs = mutableListOf(original.copy(syncMembershipTokens = listOf(tokens[0], tokens[2]))))

        val changes = mapper.toWriteSet(listOf(initial), listOf(next))

        val removed = changes.removedTokens.single()
        assertEquals(10L, removed.playlistId)
        assertEquals(original.stableKey(), removed.identityKey)
        assertEquals("b", removed.deviceId)
        assertEquals(2L, removed.counter)
        assertEquals(listOf("c" to 3L), changes.memberTokens.map { it.deviceId to it.counter })
        assertEquals(1, changes.memberTokens.single().tokenIndex)
        assertTrue(changes.removedMembers.isEmpty())
    }

    @Test
    fun `token replacement uses counter as part of the key and skips unchanged token positions`() {
        val tokens = listOf(SyncCausalToken("a", 1L), SyncCausalToken("a", 2L), SyncCausalToken("c", 3L))
        val original = song(1L).copy(syncMembershipTokens = tokens)
        val initial = playlist(10L, original)
        val nextTokens = listOf(tokens[1], SyncCausalToken("b", 4L), tokens[2])
        val next = initial.copy(songs = mutableListOf(original.copy(syncMembershipTokens = nextTokens)))

        val changes = mapper.toWriteSet(listOf(initial), listOf(next))

        assertEquals(listOf("a" to 1L), changes.removedTokens.map { it.deviceId to it.counter })
        assertEquals(listOf("a" to 2L, "b" to 4L), changes.memberTokens.map { it.deviceId to it.counter })
        assertEquals(listOf(0, 1), changes.memberTokens.map { it.tokenIndex })
        assertEquals(next.songs.single(), decode(changes.members.single().memberPayloadJson))
    }

    @Test
    fun `track candidate keeps the earlier header-only changed playlists full payload`() {
        val firstSong = song(1L).copy(customName = "first custom", matchedLyric = "first cached")
        val secondSong = firstSong.copy(customName = "second custom", matchedLyric = "second edited",
            lyricSyncEdited = true, lyricSyncRevision = 10L)
        val first = playlist(10L, firstSong)
        val second = playlist(20L, secondSong)
        val changedSecond = secondSong.copy(name = "edited title", matchedRomanizedLyric = "edited romanization")
        val next = listOf(first.copy(name = "renamed header"), second.copy(songs = mutableListOf(changedSecond)))

        val changes = mapper.toWriteSet(listOf(first, second), next)

        assertEquals(firstSong, decode(changes.tracks.single().durablePayloadJson))
        assertEquals(listOf(20L), changes.members.map { it.playlistId })
        assertEquals(changedSecond, decode(changes.members.single().memberPayloadJson))
        assertTrue(changes.memberTokens.isEmpty())
        assertTrue(changes.removedTokens.isEmpty())
    }

    @Test
    fun `track candidate follows the new global order including position-only changed headers`() {
        val firstSong = song(1L).copy(customName = "first custom")
        val secondSong = firstSong.copy(customName = "second custom")
        val first = playlist(10L, firstSong)
        val second = playlist(20L, secondSong)
        val next = listOf(second, first.copy(songs = mutableListOf(firstSong.copy(name = "edited title"))))

        val changes = mapper.toWriteSet(listOf(first, second), next)

        assertEquals(listOf(20L, 10L), changes.playlists.map { it.playlistId })
        assertEquals(secondSong, decode(changes.tracks.single().durablePayloadJson))
        assertEquals(listOf(10L), changes.members.map { it.playlistId })
    }

    @Test
    fun `identity replacement preserves local payload and schedules only the removed identity`() {
        val original = song(1L)
        val replacement = song(2L).copy(localFileName = "offline.flac", localFilePath = "/Music/offline.flac",
            sourceStableKey = "test-source-2", customName = "my title", customCoverUrl = "local-cover",
            originalName = "original title", matchedLyric = "edited original", matchedTranslatedLyric = "edited translation",
            matchedRomanizedLyric = "edited romanization", originalLyric = "cached original",
            originalTranslatedLyric = "cached translation", originalRomanizedLyric = "cached romanization",
            userLyricOffsetMs = 50L, lyricSyncEdited = true, lyricSyncRevision = 22L,
            logicalCreatedAtMs = 100L, createdAtSource = "metadata", createdAtConfidence = "exact",
            membershipAddedAtMs = 200L, sourceModifiedAtMs = 90L, streamUrl = "https://temporary-stream.example")
        val initial = playlist(10L, original)
        val next = initial.copy(songs = mutableListOf(replacement))

        val changes = mapper.toWriteSet(listOf(initial), listOf(next))

        assertEquals(listOf(original.stableKey()), changes.removedMembers[10L])
        assertEquals(setOf(original.stableKey()), changes.orphanCandidates)
        assertEquals(replacement.stableKey(), changes.members.single().identityKey)
        assertEquals(replacement.copy(streamUrl = null), decode(changes.members.single().memberPayloadJson))
        assertEquals(replacement.copy(streamUrl = null), decode(changes.tracks.single().durablePayloadJson))
        assertEquals(2, changes.memberTokens.size)
        assertTrue(changes.removedTokens.isEmpty())
    }

    @Test
    fun `removing a playlist schedules shared tracks for reference checking without rewriting the survivor`() {
        val shared = song(1L)
        val unique = song(2L)
        val first = playlist(10L, shared, unique)
        val second = playlist(20L, shared.copy(customName = "second custom"))

        val changes = mapper.toWriteSet(listOf(first, second), listOf(second))

        assertEquals(listOf(10L), changes.removedPlaylistIds)
        assertEquals(setOf(shared.stableKey(), unique.stableKey()), changes.orphanCandidates)
        assertTrue(changes.removedMembers.isEmpty())
        assertTrue(changes.members.isEmpty())
        assertTrue(changes.tracks.isEmpty())
        assertTrue(changes.memberTokens.isEmpty())
        assertTrue(changes.removedTokens.isEmpty())
    }

    @Test
    fun `reorder timestamps remain durable changes while causal tokens stay untouched`() {
        val first = song(1L)
        val second = song(2L)
        val initial = playlist(10L, first, second)
        val next = initial.copy(songs = mutableListOf(second.copy(addedAt = 500L), first.copy(addedAt = 499L)))

        val changes = mapper.toWriteSet(listOf(initial), listOf(next))

        assertEquals(listOf(500L, 499L), changes.members.map { it.addedAt })
        assertEquals(next.songs, changes.members.map { decode(it.memberPayloadJson) })
        assertEquals(2, changes.tracks.size)
        assertTrue(changes.positions.isEmpty())
        assertTrue(changes.memberTokens.isEmpty())
        assertTrue(changes.removedTokens.isEmpty())
    }

    private fun decode(payload: String): SongItem = Gson().fromJson(payload, SongItem::class.java)

    private fun playlist(id: Long, vararg songs: SongItem) = LocalPlaylist(
        id = id, name = "playlist-$id", songs = songs.toMutableList(), modifiedAt = 300L
    )

    private fun song(id: Long) = SongItem(
        id = id, name = "song-$id", artist = "artist-$id", album = "netease", albumId = id + 10L,
        durationMs = 180_000L, coverUrl = null, channelId = "netease", audioId = id.toString(), addedAt = 100L - id,
        syncMembershipTokens = listOf(SyncCausalToken("actor-a", id), SyncCausalToken("actor-z", id))
    )
}
