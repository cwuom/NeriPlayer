package moe.ouom.neriplayer.data.sync.archive

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncLogEntry
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackBucket
import moe.ouom.neriplayer.data.model.sync.SyncBiliVideoSkipRule
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlinx.coroutines.CancellationException

class SyncArchiveRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun networkLyricCachesNeverEnterTheArchive() = runBlocking {
        val cached = SyncSong(id = 1, lyricSyncEdited = false,
            matchedLyric = "普通联网歌词正文", matchedTranslatedLyric = "普通联网翻译正文",
            matchedRomanizedLyric = "普通联网音译正文", originalLyric = "原词缓存正文", originalTranslatedLyric = "原翻译缓存正文")
        val repository = SyncArchiveRepository(temporary.newFolder())
        val input = SyncData(lastModified = 1, playlists = listOf(SyncPlaylist(id = 1, songs = listOf(cached))))
        repository.prepare(input).use { archive ->
            repository.visit(archive.content, { error("writer cache must be complete") }) { kind, payload ->
                assertNotEquals(15, kind)
                assertFalse(String(payload, Charsets.UTF_8).contains("正文"))
            }
            val restored = repository.read(archive.content) { error("writer cache must be complete") }.getOrThrow()
            assertEquals(SyncSongLyricMergePolicy.normalize(cached), restored.playlists.single().songs.single())
            assertTrue(restored.lyricOverrides.isEmpty())
        }
    }

    @Test fun cancellationStopsLargeArchivePreparationBeforePublishingAManifest() {
        val repository = SyncArchiveRepository(temporary.newFolder())
        val songs = object : AbstractList<SyncSong>() {
            override val size = 1_000_000
            var visited = 0
            override fun get(index: Int): SyncSong { visited++; return SyncSong(id = index + 1L) }
        }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            repository.prepare(SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = songs)))) {
                if (++checks == 2) throw CancellationException("synthetic cancellation")
            }
        }
        assertTrue("cancelled preparation traversed ${songs.visited} songs", songs.visited < 2_000)
        assertTrue(repository.lastReferencedPaths.isEmpty())
    }

    @Test fun everySectionRoundTripsWithOneSharedEditedLyricPayload() = runBlocking {
        val song = SyncSong(id = 42, name = "edited", matchedLyric = "用户原文", matchedTranslatedLyric = "translation",
            matchedRomanizedLyric = "", lyricSyncEdited = true, lyricSyncRevision = 100)
        val input = SyncSongLyricMergePolicy.converge(SyncData(lastModified = 1,
            playlists = listOf(SyncPlaylist(id = 1, songs = listOf(song))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(id = 2, songs = listOf(song))),
            recentPlays = listOf(SyncRecentPlay(songId = 42, song = song)),
            syncLog = listOf(SyncLogEntry(timestamp = 1)),
            recentPlayDeletions = listOf(SyncRecentPlayDeletion(songId = 8, deletedAt = 2)),
            playbackStats = listOf(SyncTrackStat(id = 42, playCount = 3)),
            playbackStatBuckets = listOf(SyncPlaybackStatBucket(id = 42, dayStartAt = 2)),
            playlistSongDeletions = listOf(SyncPlaylistSongDeletion(playlistId = 1, songId = 8, deletedAt = 2)),
            playlistUsageStats = listOf(SyncPlaylistUsageStat(playlistKey = "playlist:1", openCount = 2)),
            localPlaylistPlaybackStats = listOf(SyncLocalPlaylistPlaybackStat(playlistId = 1, totalPlayCount = 3)),
            localPlaylistPlaybackBuckets = listOf(SyncLocalPlaylistPlaybackBucket(playlistId = 1, playCount = 3)),
            biliVideoSkipRules = listOf(SyncBiliVideoSkipRule(bvid = "BV17x411w7KC", modifiedAt = 2))))
        val repository = SyncArchiveRepository(temporary.newFolder())
        repository.prepare(input).use { archive ->
            var lyricCopies = 0
            repository.visit(archive.content, { error("writer cache must be complete") }) { kind, payload ->
                if (String(payload, Charsets.UTF_8).contains("用户原文")) {
                    assertEquals(15, kind)
                    lyricCopies++
                }
            }
            assertEquals(1, lyricCopies)
            assertEquals(input, repository.read(archive.content) { error("writer cache must be complete") }.getOrThrow())
        }
    }

    @Test fun aFullUserLyricSpanningManyBlocksIsSharedAndRestoredWithoutTruncation() = runBlocking {
        val song = SyncSong(id = 42, name = "edited", lyricSyncEdited = true, lyricSyncRevision = 100,
            matchedLyric = "用户修改歌词正文\n".repeat(200_000),
            matchedTranslatedLyric = "用户翻译全文\n".repeat(100_000),
            matchedRomanizedLyric = "yong hu xiu gai\n".repeat(100_000))
        val input = SyncSongLyricMergePolicy.converge(SyncData(lastModified = 1,
            playlists = listOf(SyncPlaylist(id = 1, songs = listOf(song))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(id = 2, songs = listOf(song))),
            recentPlays = listOf(SyncRecentPlay(songId = 42, song = song))))
        val repository = SyncArchiveRepository(temporary.newFolder())
        repository.prepare(input).use { archive ->
            assertEquals(4, SyncArchiveRepository.protocolVersion(archive.content))
            assertTrue(SyncArchiveRepository.originalManifest(archive.content).chunkCount > 1)
            val remote = archive.objects.associate { it.path to it.content }
            val reader = SyncArchiveRepository(temporary.newFolder())
            val restored = reader.read(archive.content) { Result.success(remote.getValue(it)) }.getOrThrow()
            assertEquals(input, restored)
            assertEquals(1, restored.lyricOverrides.size)
            val broken = SyncArchiveRepository(temporary.newFolder()).read(archive.content) {
                Result.success(remote.getValue(it).copyOf(1))
            }
            assertTrue(broken.isFailure)
        }
    }

    @Test fun roundTripPreservesMembershipDeletionsAndStatistics() = runBlocking {
        val input = SyncData(deviceId = "one", lastModified = 10,
            playlists = listOf(SyncPlaylist(id = 8, songs = (1L..1000).map { SyncSong(id = it, addedAt = it, lyricSyncEdited = false) })),
            playbackStats = (1L..2100).map { SyncTrackStat(identityKey = "netease:$it", id = it, playCount = 7) })
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.prepare(input).use { prepared ->
            val remote = prepared.objects.associate { it.path to it.content }
            val reader = SyncArchiveRepository(temporary.newFolder())
            val actual = reader.read(prepared.content) { Result.success(remote.getValue(it)) }.getOrThrow()
            assertEquals(input, actual)
            assertEquals(prepared.paths, reader.lastReferencedPaths)
            assertTrue(prepared.content.size <= SyncArchiveLimits.MAX_OBJECT_BYTES)
            assertTrue(remote.values.all { it.size <= SyncArchiveLimits.MAX_OBJECT_BYTES })
        }
    }

    @Test fun unchangedSnapshotUsesIdenticalObjectsAndNoNetworkOnCachedRead() = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        val data = SyncData(lastModified = 1, playlists = listOf(SyncPlaylist(id = 1,
            songs = (1L..20000).map { SyncSong(id = it, name = "song-$it", lyricSyncEdited = false) })))
        writer.prepare(data).use { first ->
            writer.prepare(data.copy(lastModified = 2)).use { second ->
                assertEquals(first.paths, second.paths)
                val remote = first.objects.associate { it.path to it.content }
                val reader = SyncArchiveRepository(temporary.newFolder())
                reader.read(first.content) { Result.success(remote.getValue(it)) }.getOrThrow()
                val restored = reader.read(first.content) { error("cached object was downloaded: $it") }.getOrThrow()
                assertEquals(data, restored)
            }
        }
    }

    @Test fun missingOrCorruptObjectFailsWithoutReturningPartialSnapshot() = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.prepare(SyncData(playbackStats = listOf(SyncTrackStat(id = 1)))).use { prepared ->
            val missing = SyncArchiveRepository(temporary.newFolder())
                .read(prepared.content) { Result.failure(IOException("missing")) }
            assertTrue(missing.isFailure)
            val corrupt = SyncArchiveRepository(temporary.newFolder())
                .read(prepared.content) { Result.success(byteArrayOf(1, 2, 3)) }
            assertTrue(corrupt.isFailure)
            val damagedRoot = prepared.content.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            assertTrue(SyncArchiveRepository(temporary.newFolder()).read(damagedRoot) {
                error("bad root must fail before downloading")
            }.isFailure)
        }
    }

    @Test fun changingOneSongReusesMostContentBlocks() {
        val writer = SyncArchiveRepository(temporary.newFolder())
        val songs = (1L..100000).map { SyncSong(id = it, name = "song-$it", artist = "artist-${it % 500}") }
        val firstData = SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = songs)))
        writer.prepare(firstData).use { first ->
            val updated = songs.toMutableList().also { it[50000] = it[50000].copy(customName = "edited") }
            writer.prepare(firstData.copy(playlists = listOf(SyncPlaylist(id = 1, songs = updated)))).use { second ->
                assertTrue("content boundaries should recover after a small edit", (first.paths intersect second.paths).size >= first.paths.size / 2)
                val bytes = second.objects.filter { it.path !in first.paths }.sumOf { it.content.size.toLong() }
                assertTrue("single edit uploaded $bytes bytes", bytes < 3 * 1024 * 1024)
            }
        }
    }

    @Test fun aForgedRecordLengthFailsBeforeAllocatingItsClaimedPayload() = runBlocking {
        val cacheDirectory = temporary.newFolder()
        val raw = byteArrayOf(8, 4, 0, 0, 0)
        val ref = SyncArchiveCache(cacheDirectory).store(raw, false)
        val manifest = SyncArchiveManifest(3, SyncData(lastModified = 0), ref, 1, raw.size.toLong(), 1)
        val result = SyncArchiveRepository(cacheDirectory).read(SyncArchiveCodec.manifest(manifest)) {
            error("cache should be complete")
        }
        assertTrue(result.isFailure)
        assertEquals("Invalid sync record size", result.exceptionOrNull()?.message)
    }

    @Test fun corruptCacheIsRecoveredFromVerifiedRemoteObjects() = runBlocking {
        val cacheDirectory = temporary.newFolder()
        val repository = SyncArchiveRepository(cacheDirectory)
        val input = SyncData(lastModified = 1, playbackStats = listOf(SyncTrackStat(id = 1)))
        repository.prepare(input).use { archive ->
            val remote = archive.objects.associate { it.path to it.content }
            cacheDirectory.listFiles().orEmpty().filter { it.name.endsWith(".zst") }.forEach { it.writeBytes(byteArrayOf(7)) }
            var downloads = 0
            val restored = repository.read(archive.content) {
                downloads++
                Result.success(remote.getValue(it))
            }.getOrThrow()
            assertTrue(downloads > 0)
            assertEquals(input, restored)
        }
    }
}
