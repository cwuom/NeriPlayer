package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import com.google.gson.Gson
import moe.ouom.neriplayer.data.history.toPlayedEntry
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.backup.BackupMetadataMapper
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SyncHistoryLocalSourceTest {
    private val context = mock(Context::class.java)
    private val reference = "content://media/external/audio/media/42"
    private val selected = SongItem(
        id = 42L, name = "Scanned title", artist = "Artist", album = "Album", albumId = 0L,
        durationMs = 1_000L, coverUrl = null, mediaUri = reference,
        channelId = "netease", audioId = "42", explicitLocalSourceReference = reference
    )

    @Test
    fun `history sync and backup projections never export local source provenance`() {
        val entry = selected.toPlayedEntry(1L)

        assertTrue(buildRecentPlaySyncSnapshots(listOf(entry), { "device" }, context).isEmpty())
        val backupJson = Gson().toJson(BackupMetadataMapper.toSyncRecentPlay(entry))
        assertFalse(backupJson.contains("explicitLocalSourceReference"))
        assertFalse(backupJson.contains(reference))
    }

    @Test
    fun `remote history cannot inject local provenance while current device local history keeps its selection`() {
        val remote = Gson().fromJson("""{
            "songId":7,"playedAt":2,"deviceId":"remote","song":{
                "id":7,"name":"Remote","artist":"Artist","album":"netease","albumId":1,
                "durationMs":1000,"explicitLocalSourceReference":"$reference"
            }
        }""", SyncRecentPlay::class.java)
        val local = selected.toPlayedEntry(1L)

        val restored = SyncLocalRestoreMapping(context).history(SyncData(recentPlays = listOf(remote)), listOf(local))

        assertEquals(reference, restored.single { it.id == 42L }.toSongItem().explicitLocalSourceReference)
        assertNull(restored.single { it.id == 7L }.explicitLocalSourceReference)
        assertNull(restored.single { it.id == 7L }.toSongItem().explicitLocalSourceReference)
        assertNull(BackupMetadataMapper.toPlayedEntry(remote, context)?.explicitLocalSourceReference)
    }

    @Test
    fun `same device sync keeps path only local history and its selected source when recent plays are empty`() {
        val path = "/storage/emulated/0/Music/Album/selected.flac"
        val local = selected.copy(
            album = "Real album", albumId = 99L, mediaUri = null, localFilePath = path,
            explicitLocalSourceReference = path
        ).toPlayedEntry(1L)
        assertTrue(buildRecentPlaySyncSnapshots(listOf(local), { "device" }, context).isEmpty())

        val restored = SyncLocalRestoreMapping(context).history(SyncData(recentPlays = emptyList()), listOf(local))

        assertEquals(listOf(local), restored)
        assertEquals(path, restored.single().toSongItem().explicitLocalSourceReference)
    }

    @Test
    fun `blank paths neither preserve remote history nor grant local source provenance`() {
        listOf(null, "", " \t ").forEach { path ->
            val remote = selected.copy(
                album = "Real album", albumId = 99L, mediaUri = null, localFilePath = path,
                explicitLocalSourceReference = path
            ).toPlayedEntry(1L)
            assertNull(remote.explicitLocalSourceReference)
            assertNull(remote.copy(explicitLocalSourceReference = path).toSongItem().explicitLocalSourceReference)

            val restored = SyncLocalRestoreMapping(context).history(SyncData(recentPlays = emptyList()), listOf(remote))

            assertTrue(restored.isEmpty())
        }
    }
}
