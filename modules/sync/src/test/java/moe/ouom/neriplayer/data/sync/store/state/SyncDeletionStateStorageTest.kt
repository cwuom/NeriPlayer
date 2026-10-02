package moe.ouom.neriplayer.data.sync.store.state

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDeletionStateStorageTest {
    private val prefs = MemorySyncPreferences()
    private val store = SecureTokenStorage(prefs.preferences)

    @Test
    fun `playlist legacy ids migrate once with mutation version in same commit`() {
        prefs.values["deleted_playlist_ids"] = "7,8,7"
        assertEquals(setOf(7L, 8L), store.getDeletedPlaylistIds())
        val migrated = store.getDeletedPlaylistTimestamps()
        assertTrue(migrated.values.all { it > 0L })
        assertEquals(migrated, store.getDeletedPlaylistTimestamps())
        assertEquals(1L, store.getSyncMutationVersion())
        assertEquals(listOf(setOf("deleted_playlist_timestamps", "sync_mutation_version")), prefs.commits)
    }

    @Test
    fun `damaged legacy playlist ids cannot silently forget a deletion`() {
        for (raw in listOf("7,bad,8", "7,,8", "7,", "9223372036854775808", "null")) {
            prefs.values["deleted_playlist_ids"] = raw
            assertThrows(IllegalStateException::class.java) { store.getDeletedPlaylistIds() }
            assertThrows(IllegalStateException::class.java) { store.addDeletedPlaylistId(9) }
            assertEquals(raw, prefs.values["deleted_playlist_ids"])
            assertEquals(0L, store.getSyncMutationVersion())
        }
        prefs.values["deleted_playlist_ids"] = ""
        assertTrue(store.getDeletedPlaylistIds().isEmpty())
    }

    @Test
    fun `JSON playlist ids preserve exact integers and reject malformed removal evidence`() {
        prefs.values[KEY_DELETED_PLAYLIST_IDS] = "[7,8,7,9223372036854775807]"
        assertEquals(setOf(7L, 8L, Long.MAX_VALUE), store.getDeletedPlaylistIds())
        for (raw in listOf("[\"7\"]", "[7.0]", "[7,null]", "[9223372036854775808]", "[7]{}")) {
            prefs.values[KEY_DELETED_PLAYLIST_IDS] = raw
            assertThrows(IllegalStateException::class.java) { store.getDeletedPlaylistIds() }
            assertThrows(IllegalStateException::class.java) { store.addDeletedPlaylistId(9) }
            assertEquals(raw, prefs.values[KEY_DELETED_PLAYLIST_IDS])
            assertEquals(0L, store.getSyncMutationVersion())
        }
        prefs.values[KEY_DELETED_PLAYLIST_IDS] = "[]"
        assertTrue(store.getDeletedPlaylistIds().isEmpty())
    }

    @Test
    fun `playlist deletion removal changes epoch only when state changes`() {
        store.clearDeletedPlaylistIds()
        store.removeDeletedPlaylistIds(emptySet())
        store.addDeletedPlaylistId(7)
        store.removeDeletedPlaylistIds(setOf(8))
        assertEquals(1L, store.getSyncMutationVersion())
        store.addDeletedPlaylistId(8)
        store.removeDeletedPlaylistIds(setOf(7))
        assertEquals(setOf(8L), store.getDeletedPlaylistTimestamps().keys)
        store.clearDeletedPlaylistIds()
        assertTrue(store.getDeletedPlaylistIds().isEmpty())
        assertTrue(store.getDeletedPlaylistTimestamps().isEmpty())
        assertEquals(4L, store.getSyncMutationVersion())
    }

    @Test
    fun `removing final playlist clears ids and timestamps together`() {
        store.addDeletedPlaylistId(7)
        store.removeDeletedPlaylistIds(setOf(7))
        assertFalse(prefs.values.containsKey("deleted_playlist_ids"))
        assertFalse(prefs.values.containsKey("deleted_playlist_timestamps"))
    }

    @Test
    fun `history merges newest tombstone with deterministic device tie breaker`() {
        store.addRecentPlayDeletions(listOf(recent(1, 10, "a"), recent(1, 10, "z"), recent(1, 5)))
        assertEquals("z", store.getRecentPlayDeletions().single().deviceId)
        store.removeRecentPlayDeletion(SongIdentity(2, "netease", null))
        assertEquals(1L, store.getSyncMutationVersion())
        store.removeRecentPlayDeletion(SongIdentity(1, "netease", null))
        assertTrue(store.getRecentPlayDeletions().isEmpty())
        assertEquals(2L, store.getSyncMutationVersion())
        store.addRecentPlayDeletions(emptyList())
    }

    @Test
    fun `legacy history source labels normalize through read add guarded replace and removal`() {
        prefs.values["recent_play_deletions"] = """[{"songId":7,"album":"Netease","deletedAt":20,"deviceId":"legacy"}]"""
        assertEquals(listOf(recent(7, 20, "legacy")), store.getRecentPlayDeletions())
        store.addRecentPlayDeletions(listOf(recent(7, 30, "new").copy(album = "album")))
        assertEquals(listOf(recent(7, 30, "new")), store.getRecentPlayDeletions())
        assertEquals(1L, store.getSyncMutationVersion())
        assertTrue(store.setDeletionStateIfMutationVersion(1, listOf(recent(7, 40).copy(album = "Netease")), emptyList()))
        assertEquals(listOf(recent(7, 40)), store.getRecentPlayDeletions())
        assertEquals(1L, store.getSyncMutationVersion())
        store.removeRecentPlayDeletion(SongIdentity(7, "Netease", null))
        assertTrue(store.getRecentPlayDeletions().isEmpty())
        assertEquals(2L, store.getSyncMutationVersion())
        store.addRecentPlayDeletions(listOf(recent(7).copy(album = "Netease")))
        store.removeRecentPlayDeletion(SongIdentity(7, "netease", null))
        assertTrue(store.getRecentPlayDeletions().isEmpty())
    }

    @Test
    fun `history retains every deletion without bumping epoch on sync replace`() {
        store.setRecentPlayDeletions((1L..505L).map { recent(it, it) })
        assertEquals(505, store.getRecentPlayDeletions().size)
        assertEquals(505L, store.getRecentPlayDeletions().first().deletedAt)
        assertEquals(0L, store.getSyncMutationVersion())
        store.setRecentPlayDeletions(emptyList())
        assertFalse(prefs.values.containsKey("recent_play_deletions"))
    }

    @Test
    fun `usage deletion normalizes keys timestamps and duplicate maximum`() {
        store.addPlaylistUsageDeletion(" ", 7)
        store.removePlaylistUsageDeletion(" ")
        store.addPlaylistUsageDeletion(" playlist ", -7)
        store.addPlaylistUsageDeletion("playlist", 20)
        store.addPlaylistUsageDeletion("playlist", 5)
        assertEquals(mapOf("playlist" to 20L), store.getPlaylistUsageDeletions())
        store.removePlaylistUsageDeletion("missing")
        assertEquals(3L, store.getSyncMutationVersion())
        store.removePlaylistUsageDeletion("playlist", bumpVersion = false)
        assertTrue(store.getPlaylistUsageDeletions().isEmpty())
        assertEquals(3L, store.getSyncMutationVersion())
    }

    @Test
    fun `legacy usage JSON normalizes invalid entries and retains every valid deletion`() {
        prefs.values["playlist_usage_deletions"] = """{" playlist ":3,"playlist":9,"bad":null," ":17,"old":-2}"""
        assertEquals(mapOf("playlist" to 9L, "old" to 1L), store.getPlaylistUsageDeletions())
        prefs.values["playlist_usage_deletions"] = (1..505).joinToString(",", "{", "}") { "\"$it\":$it" }
        assertEquals(505, store.getPlaylistUsageDeletions().size)
    }

    @Test
    fun `readding a song only clears legacy deletion and keeps observed remove token`() {
        val tokenDeletion = song(7, 2).copy(removedMembershipTokens = listOf(SyncCausalToken("old", 1)))
        store.addPlaylistSongDeletions(listOf(song(7, 1), tokenDeletion, song(8, 1)))
        store.removePlaylistSongDeletions(7, listOf(SongIdentity(1, "netease", null), SongIdentity(2, "netease", null)))
        assertEquals(setOf(tokenDeletion, song(8, 1)), store.getPlaylistSongDeletions().toSet())
        store.removePlaylistSongDeletions(7, emptyList())
        store.removePlaylistSongDeletionsForPlaylist(99)
        assertEquals(2L, store.getSyncMutationVersion())
        store.removePlaylistSongDeletionsForPlaylist(7)
        assertEquals(listOf(song(8, 1)), store.getPlaylistSongDeletions())
        store.removePlaylistSongDeletionsForPlaylist(8)
        assertTrue(store.getPlaylistSongDeletions().isEmpty())
        store.addPlaylistSongDeletions(emptyList())
    }

    @Test
    fun `guarded replace rejects stale epoch and atomically stores both sections`() {
        val version = store.markSyncMutation()
        assertFalse(store.setDeletionStateIfMutationVersion(version - 1, listOf(recent(1)), listOf(song(7, 1))))
        assertTrue(store.setDeletionStateIfMutationVersion(version, listOf(recent(1)), listOf(song(7, 1))))
        assertEquals(setOf("recent_play_deletions", "playlist_song_deletions"), prefs.commits.last())
        assertEquals(version, store.getSyncMutationVersion())
        assertTrue(store.setDeletionStateIfMutationVersion(version, emptyList(), emptyList()))
        assertTrue(store.getRecentPlayDeletions().isEmpty())
        assertTrue(store.getPlaylistSongDeletions().isEmpty())
    }

    @Test
    fun `playlist transaction adds clears restores and bumps one durable epoch`() {
        store.addDeletedPlaylistId(99)
        store.setPlaylistSongDeletions(listOf(song(7, 1), song(8, 2)))
        val next = store.applyPlaylistSyncMutation(
            addedSongDeletions = listOf(song(9, 3)),
            removedSongDeletions = listOf(7L to listOf(SongIdentity(1, "netease", null))),
            deletedPlaylistIds = listOf(10), clearedPlaylistDeletionIds = listOf(8), restoredPlaylistIds = setOf(99)
        )
        assertEquals(2L, next)
        assertEquals(listOf(song(9, 3)), store.getPlaylistSongDeletions())
        assertEquals(setOf(10L), store.getDeletedPlaylistIds())
        assertEquals(setOf("playlist_song_deletions", "deleted_playlist_ids", "deleted_playlist_timestamps", "sync_mutation_version"), prefs.commits.last())
        store.applyPlaylistSyncMutation(emptyList(), emptyList(), emptyList(), listOf(9), setOf(10))
        assertTrue(store.getPlaylistSongDeletions().isEmpty())
        assertTrue(store.getDeletedPlaylistIds().isEmpty())
    }

    @Test
    fun `corrupt deletion documents stop sync instead of losing removal evidence`() {
        for (key in listOf("deleted_playlist_timestamps", "recent_play_deletions", "playlist_song_deletions", "playlist_usage_deletions")) prefs.values[key] = "broken"
        assertThrows(IllegalStateException::class.java) { store.getRecentPlayDeletions() }
        assertThrows(IllegalStateException::class.java) { store.getPlaylistSongDeletions() }
        assertThrows(IllegalStateException::class.java) { store.getPlaylistUsageDeletions() }
        prefs.values["deleted_playlist_ids"] = "7"
        assertThrows(IllegalStateException::class.java) { store.getDeletedPlaylistTimestamps() }
    }

    @Test
    fun `invalid legacy usage arrays and null documents cannot acknowledge empty state`() {
        prefs.values["playlist_usage_deletions"] = """[[null,3],["playlist",7]]"""
        assertThrows(IllegalStateException::class.java) { store.getPlaylistUsageDeletions() }
        prefs.values["playlist_usage_deletions"] = "null"
        assertThrows(IllegalStateException::class.java) { store.getPlaylistUsageDeletions() }
    }

    @Test
    fun `failed tombstone commit reports failure`() {
        prefs.failNextCommit = true
        assertThrows(IllegalStateException::class.java) { store.addRecentPlayDeletions(listOf(recent(1))) }
    }

    private fun recent(id: Long, time: Long = 10, device: String = "d") = SyncRecentPlayDeletion(songId = id, album = "netease", deletedAt = time, deviceId = device)
    private fun song(playlist: Long, id: Long) = SyncPlaylistSongDeletion(playlistId = playlist, songId = id, album = "netease", deletedAt = 10, deviceId = "d")
}
