package moe.ouom.neriplayer.data.sync.archive

import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchivePreparationCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cancellationStillEnforcesTheCacheBudget() {
        val directory = temporary.newFolder()
        val orphan = java.io.File(directory, "abandoned.zst")
        RandomAccessFile(orphan, "rw").use { it.setLength(SyncArchiveLimits.CACHE_BYTES + 1) }
        orphan.setLastModified(1)
        val repository = SyncArchiveRepository(directory)
        val data = SyncData(playlists = listOf(SyncPlaylist(id = 1,
            songs = (1L..2000).map { SyncSong(id = it, name = "synthetic-$it", artist = "metadata".repeat(128)) })))
        var checks = 0
        val cancellation = CancellationException("synthetic cancellation")
        val thrown = assertThrows(CancellationException::class.java) {
            repository.prepare(data) { if (++checks == 6) throw cancellation }
        }
        assertSame(cancellation, thrown)
        assertFalse("failed preparation retained an over-budget abandoned object", orphan.exists())
        assertFalse("failed preparation retained a temporary workspace", directory.listFiles().orEmpty().any { it.name.startsWith("sync-stage-") })
    }
}
