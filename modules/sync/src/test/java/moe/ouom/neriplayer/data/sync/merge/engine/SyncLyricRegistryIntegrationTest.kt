package moe.ouom.neriplayer.data.sync.merge.engine

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.change.SyncDataChangeDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLyricRegistryIntegrationTest {
    private val merger = SyncDataMerger(TestSyncMergeHost()) { 999L }
    private val plain = SyncSong(id = 1L, album = "netease")
    private val legacyEdit = plain.copy(matchedLyric = "legacy user edit", originalLyric = "baseline", lyricSyncEdited = true, lyricSyncRevision = 1)

    @Test
    fun `confirmed edit from deleted recent history survives in permanent registry`() {
        val local = SyncData(recentPlays = (2L..502L).map { id ->
            SyncRecentPlay(song = plain.copy(id = id), playedAt = id)
        }, recentPlayDeletions = listOf(SyncRecentPlayDeletion(songId = plain.id, album = plain.album, deletedAt = 2L)))
        val remote = SyncData(recentPlays = listOf(SyncRecentPlay(song = legacyEdit, playedAt = 1L)))

        val merged = merger.merge(local, remote, 0L).mergedData

        assertEquals(501, merged.recentPlays.size)
        assertTrue(merged.recentPlays.none { it.song.id == 1L })
        assertEquals("legacy user edit", merged.lyricOverrides.single().matchedLyric)
        assertStable(merged, remote)
    }

    @Test
    fun `newer ordinary history preserves older confirmed lyric edit without rewinding playback`() {
        val local = SyncData(recentPlays = listOf(SyncRecentPlay(song = plain, playedAt = 100L)))
        val remote = SyncData(recentPlays = listOf(SyncRecentPlay(song = legacyEdit, playedAt = 1L)))

        val merged = merger.merge(local, remote, 0L).mergedData

        assertEquals(100L, merged.recentPlays.single().playedAt)
        assertEquals("legacy user edit", merged.recentPlays.single().song.matchedLyric)
        assertEquals("legacy user edit", merged.lyricOverrides.single().matchedLyric)
        assertStable(merged, remote)
    }

    @Test
    fun `favorite container payload selection cannot discard a confirmed user edit`() {
        val favorite = SyncFavoritePlaylist(id = 1L, source = "netease", songs = listOf(plain), modifiedAt = 100L)
        val local = SyncData(favoritePlaylists = listOf(favorite))
        val remote = SyncData(favoritePlaylists = listOf(favorite.copy(songs = listOf(legacyEdit), modifiedAt = 1L)))

        val merged = merger.merge(local, remote, 0L).mergedData

        assertEquals(100L, merged.favoritePlaylists.single().modifiedAt)
        assertEquals("legacy user edit", merged.favoritePlaylists.single().songs.single().matchedLyric)
        assertEquals(1L, merged.lyricOverrides.single().lyricSyncRevision)
        assertStable(merged, remote)
    }

    private fun assertStable(merged: SyncData, originalRemote: SyncData) {
        val repeated = merger.merge(merged, originalRemote, 0L).mergedData
        assertFalse(SyncDataChangeDetector.hasDataChanged(merged, repeated))
        assertFalse(SyncDataChangeDetector.hasDataChanged(merged, merger.initial(merged).mergedData))
    }
}
