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

class SyncArchiveScaleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun millionPlaylistMembersAreEncodedWithoutWholePlaylistBuffer() = runBlocking {
        assumeTrue(System.getProperty("runSyncScale").toBoolean())
        val songs = object : AbstractList<SyncSong>() {
            override val size = 1_000_000
            override fun get(index: Int) = SyncSong(id = index + 1L, name = "歌曲-$index", artist = "artist-${index % 500}",
                addedAt = 1_700_000_000_000L + index, lyricSyncEdited = false)
        }
        val repository = SyncArchiveRepository(temporary.newFolder())
        val started = System.nanoTime()
        val data = SyncData(lastModified = 1, playlists = listOf(SyncPlaylist(id = 1, songs = songs)))
        repository.prepare(data).use { archive ->
            var count = 0
            repository.visit(archive.content, { error("writer cache must be complete") }) { kind, payload ->
                if (kind == 2) {
                    if (count % 100_000 == 0 || count == songs.lastIndex) assertEquals(songs[count], ProtoBuf.decodeFromByteArray<SyncSong>(payload))
                    count++
                }
            }
            assertEquals(songs.size, count)
            report("million-members", archive, started)
            val inserted = object : AbstractList<SyncSong>() {
                override val size = songs.size + 1
                override fun get(index: Int): SyncSong = when {
                    index < 500_000 -> songs[index]
                    index == 500_000 -> SyncSong(id = 2_000_000, name = "新增歌曲", lyricSyncEdited = false)
                    else -> songs[index - 1]
                }
            }
            val editStarted = System.nanoTime()
            repository.prepare(data.copy(playlists = listOf(SyncPlaylist(id = 1, songs = inserted)))).use { updated ->
                val changedObjects = updated.objects(archive.paths).toList()
                val changedBytes = changedObjects.sumOf { it.content.size.toLong() } + updated.content.size
                assertTrue("single insertion transferred $changedBytes bytes", changedBytes < 3 * 1024 * 1024)
                assertTrue((archive.paths intersect updated.paths).size >= archive.paths.size / 2)
                println("SYNC_DELTA name=million-members-single-insertion wire=$changedBytes objects=${changedObjects.size} elapsedMs=${(System.nanoTime() - editStarted) / 1_000_000}")
            }
        }
    }

    @Test fun tenMillionStatisticsRecordsHaveNoGlobalWireLimit() = runBlocking {
        assumeTrue(System.getProperty("runSyncScale").toBoolean())
        val records = object : AbstractList<SyncTrackStat>() {
            override val size = 10_000_000
            override fun get(index: Int) = SyncTrackStat(id = index + 1L, identityKey = "netease:${index + 1L}",
                name = "歌曲-$index", artist = "artist-${index % 500}", totalListenMs = 600_000L,
                playCount = 3, firstPlayedAt = 1_700_000_000_000L, lastPlayedAt = 1_700_000_600_000L)
        }
        val repository = SyncArchiveRepository(temporary.newFolder())
        val started = System.nanoTime()
        repository.prepare(SyncData(lastModified = 1, playbackStats = records)).use { archive ->
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

    private fun report(name: String, archive: SyncPreparedArchive, started: Long) {
        assertEquals(4, SyncArchiveRepository.protocolVersion(archive.content))
        val manifest = SyncArchiveRepository.originalManifest(archive.content)
        val wire = archive.objects.sumOf { it.content.size.toLong() } + archive.content.size
        println("SYNC_SCALE name=$name records=${manifest.recordCount} raw=${manifest.rawDataBytes} wire=$wire objects=${archive.paths.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
    }
}
