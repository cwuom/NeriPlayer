@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SyncArchiveScaleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun millionPlaylistMembersExceedRetainedCapacityWithoutPublishingOrLeakingAWorkspace() {
        assumeTrue(System.getProperty("runSyncScale").toBoolean())
        var visited = 0
        val songs = object : AbstractList<SyncSong>() {
            override val size = 1_000_000
            override fun get(index: Int): SyncSong {
                visited++
                return SyncSong(id = index + 1L, name = "歌曲-$index", artist = "artist-${index % 500}",
                    addedAt = 1_700_000_000_000L + index, lyricSyncEdited = false)
            }
        }
        val directory = temporary.newFolder()
        val repository = SyncArchiveRepository(directory)
        val started = System.nanoTime()
        val data = SyncData(lastModified = 1, playlists = listOf(SyncPlaylist(id = 1, songs = songs)))
        assertThrows(IOException::class.java) { repository.prepare(data).close() }
        assertTrue("capacity rejection traversed the whole playlist", visited < songs.size)
        assertTrue(repository.lastReferencedPaths.isEmpty())
        assertFalse(directory.listFiles().orEmpty().any { it.isDirectory && it.name.startsWith("sync-stage-") })
        println("SYNC_SCALE name=million-members-capacity-rejection visited=$visited elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
    }

    @Test fun tenMillionStatisticsRecordsHaveNoGlobalWireLimit() = runBlocking {
        assumeTrue(System.getProperty("runSyncScale").toBoolean())
        val records = object : AbstractList<SyncTrackStat>() {
            override val size = 10_000_000
            override fun get(index: Int) = SyncTrackStat(id = index + 1L, identityKey = "netease:${(index + 1L).toString().padStart(8, '0')}",
                name = "歌曲-$index", artist = "artist-${index % 500}", totalListenMs = 600_000L,
                playCount = 3, firstPlayedAt = 1_700_000_000_000L, lastPlayedAt = 1_700_000_600_000L)
        }
        val repository = SyncArchiveRepository(temporary.newFolder())
        val started = System.nanoTime()
        repository.playbackDatasets.fromLegacy(SyncData(lastModified = 1, playbackStats = records)).use { dataset ->
            repository.prepareCancellable(dataset).use { archive ->
                var count = 0
                repository.visit(archive.content, { error("writer cache must be complete") }) { kind, payload ->
                    assertEquals(8, kind)
                    if (count % 1_000_000 == 0 || count == records.lastIndex) assertEquals(records[count], ProtoBuf.decodeFromByteArray<SyncTrackStat>(payload))
                    count++
                }
                assertEquals(records.size, count)
                assertTrue(archive.objects.all { it.content.size <= 2 * 1024 * 1024 })
                report("ten-million-statistics", archive, started)
            }
        }
    }

    private fun report(name: String, archive: SyncPreparedArchive, started: Long) {
        assertEquals(4, SyncArchiveRepository.protocolVersion(archive.content))
        val manifest = SyncArchiveRepository.originalManifest(archive.content)
        val wire = archive.objects.sumOf { it.content.size.toLong() } + archive.content.size
        println("SYNC_SCALE name=$name records=${manifest.recordCount} raw=${manifest.rawDataBytes} wire=$wire objects=${archive.paths.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
    }
}
