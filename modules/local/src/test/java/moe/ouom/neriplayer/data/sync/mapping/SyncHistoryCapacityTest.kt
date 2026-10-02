package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.mock

class SyncHistoryCapacityTest {
    @After
    fun clearCoverMapper() {
        CoverUrlMapper.installForTest(null)
    }

    @Test
    fun `snapshot keeps every eligible song beyond old history capacity`() {
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
        val remote = (1L..1501L).map(::entry)
        val local = entry(2000L).copy(mediaUri = "content://local/2000")
        val localFile = entry(2001L).copy(localFilePath = "/local/2001")

        val snapshot = buildRecentPlaySyncSnapshots(remote + local + localFile, { "device" }, mock(Context::class.java))

        assertEquals(remote.map { it.id }, snapshot.map { it.songId })
        assertEquals(remote.map { it.playedAt }, snapshot.map { it.playedAt })
        assertEquals(remote.map { it.resumePositionMs }, snapshot.map { it.resumePositionMs })
        assertEquals(setOf("device"), snapshot.map { it.deviceId }.toSet())
    }

    @Test
    fun `restoration keeps every remote identity and owned local history beyond old capacity`() {
        val remote = (1L..1501L).map { id ->
            SyncRecentPlay(id, SyncSong(id = id, name = "song $id", album = "netease", albumId = 1L), id, resumePositionMs = id * 2L)
        }
        val local = entry(2000L).copy(mediaUri = "content://local/2000")
        val replaced = entry(3000L)
        val invalid = remote.first().copy(song = remote.first().song.copy(mediaUri = "content://remote-local/1"))

        val restored = SyncLocalRestoreMapping(mock(Context::class.java)).history(
            SyncData(recentPlays = remote + remote.first() + invalid), listOf(local, replaced)
        )

        assertEquals(1502, restored.size)
        assertEquals(remote.map { it.songId }.toSet() + local.id, restored.map { it.id }.toSet())
        assertEquals(2L, restored.single { it.id == 1L }.resumePositionMs)
        assertFalse(restored.any { it.id == replaced.id })
        assertEquals(restored.sortedByDescending { it.playedAt }, restored)
    }

    private fun entry(id: Long) = PlayedEntry(
        id = id, name = "song $id", artist = "artist", album = "netease", albumId = 1L,
        durationMs = 100L, coverUrl = null, playedAt = id, resumePositionMs = id * 2L
    )
}
