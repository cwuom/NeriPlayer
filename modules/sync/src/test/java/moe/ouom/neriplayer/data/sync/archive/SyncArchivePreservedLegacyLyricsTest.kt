package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format

import kotlinx.coroutines.test.runTest
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchivePreservedLegacyLyricsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy = SyncSong(id = 7, album = "Netease", matchedLyric = "old modified text",
        matchedTranslatedLyric = "old translation", matchedRomanizedLyric = "",
        originalLyric = "baseline text", originalTranslatedLyric = "baseline translation",
        originalRomanizedLyric = "baseline romanized\r\n")
    private val omitted = legacy.copy(matchedLyric = null, matchedTranslatedLyric = null,
        matchedRomanizedLyric = null, originalLyric = null, originalTranslatedLyric = null,
        originalRomanizedLyric = null, lyricSyncEdited = false)

    @Test
    fun `first dataset read restores preserved lyrics into song and override before returning`() = runTest {
        val published = publishOmitted()
        val recovery = MemoryRecovery()
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        reader.readDataset(published.first, FileSyncPlaybackDatasetStore(temporary.newFolder()), { it }, { it }) {
            Result.success(published.second.getValue(it))
        }.getOrThrow().use { dataset ->
            assertPreserved(dataset.data)
        }
        assertEquals(1, recovery.recoveries)
    }

    @Test
    fun `completed receipts validate the complete V3 and V4 closure without repeating recovery`() = runTest {
        for (protocol in listOf(3, 4)) {
            val published = publishOmitted(protocol = protocol)
            val recovery = MemoryRecovery()
            SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
                Result.success(published.second.getValue(it))
            }.getOrThrow()
            val downloaded = mutableSetOf<String>()
            SyncArchiveRepository(temporary.newFolder(), recovery).readDataset(published.first,
                FileSyncPlaybackDatasetStore(temporary.newFolder()), { it }, { it }) { path ->
                downloaded += path
                Result.success(published.second.getValue(path))
            }.getOrThrow().use { dataset -> assertPreserved(dataset.data) }
            assertEquals(published.second.keys, downloaded)
            assertEquals(1, recovery.recoveries)
        }
    }

    @Test
    fun `completed receipts and warm caches cannot approve a remote with a deleted source object`() = runTest {
        for (protocol in listOf(3, 4)) {
            val published = publishOmitted(protocol = protocol)
            val recovery = MemoryRecovery()
            val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
            reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow().also(::assertPreserved)
            val sourcePath = if (protocol == 3) {
                requireNotNull(SyncArchiveRepository.originalManifest(published.first).legacyLyrics).root.path
            } else requireNotNull(SyncArchiveV4Format.readManifest(published.first).legacy.root).path
            val missing = IOException("remote source object deleted")
            val staging = temporary.newFolder()
            val requested = mutableSetOf<String>()
            val result = reader.readDataset(published.first, FileSyncPlaybackDatasetStore(staging), { it }, { it },
                verifyRemoteObjects = true) { path ->
                requested += path
                if (path == sourcePath) Result.failure(missing) else Result.success(published.second.getValue(path))
            }
            assertSame(missing, result.exceptionOrNull())
            assertTrue(sourcePath in requested)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertTrue(reader.lastReferencedPaths.isEmpty())
            assertEquals(1, recovery.recoveries)
        }
    }

    @Test
    fun `ordinary read also restores preserved lyrics into the main payload`() = runTest {
        val published = publishOmitted()
        val recovery = MemoryRecovery()
        val restored = SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        assertPreserved(restored)
    }

    @Test
    fun `baseline only legacy source is retained and restored as complete playback lyrics`() = runTest {
        val originalOnly = legacy.copy(matchedLyric = null, matchedTranslatedLyric = null)
        val published = publishOmitted(originalOnly)
        assertNotNull(SyncArchiveRepository.originalManifest(published.first).legacyLyrics)
        val restored = SyncArchiveRepository(temporary.newFolder(), MemoryRecovery()).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        val song = restored.playlists.single().songs.single()
        assertEquals(originalOnly.originalLyric, song.matchedLyric)
        assertEquals(originalOnly.originalTranslatedLyric, song.matchedTranslatedLyric)
        assertEquals(originalOnly.originalLyric, song.originalLyric)
        assertEquals(true, song.lyricSyncEdited)
        assertEquals(1L, song.lyricSyncRevision)
    }

    @Test
    fun `completed receipt failures clean staging after validating either protocol and remain retryable`() = runTest {
        for (protocol in listOf(3, 4)) {
            val published = publishOmitted(protocol = protocol)
            val recovery = MemoryRecovery()
            SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
                Result.success(published.second.getValue(it))
            }.getOrThrow()
            val failure = IOException("durable candidate read failed")
            recovery.readFailure = failure
            val staging = temporary.newFolder()
            val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
            val downloaded = mutableSetOf<String>()
            val result = reader.readDataset(published.first, FileSyncPlaybackDatasetStore(staging), { it }, { it }) { path ->
                downloaded += path
                Result.success(published.second.getValue(path))
            }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertEquals(published.second.keys, downloaded)
            recovery.readFailure = null
            reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow().also(::assertPreserved)
            assertEquals(1, recovery.recoveries)
        }
    }

    @Test
    fun `historical optimization preference cannot remove any preserved lyric field`() = runTest {
        val published = publishOmitted()
        val recovery = MemoryRecovery().apply { optimize = true }
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val restored = reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow()
        assertPreserved(restored)
        assertEquals(listOf(legacy), recovery.preservedLyrics())
        reader.prepare(restored).use { prepared ->
            assertEquals(SyncArchiveRepository.originalManifest(published.first).legacyLyrics,
                SyncArchiveRepository.originalManifest(prepared.content).legacyLyrics)
            assertPreserved(reader.read(prepared.content) { error("writer cache must be complete") }.getOrThrow())
        }
    }

    @Test
    fun `historical optimization preference retains every source including Netease and YouTube`() = runTest {
        val bilibili = legacy.copy(id = 8, album = "Bilibili")
        val youtube = legacy.copy(id = 9, album = "YouTube", channelId = "youtube")
        val candidates = listOf(legacy, bilibili, youtube)
        val writer = SyncArchiveRepository(temporary.newFolder(), MemoryRecovery().apply { optimize = true })
        writer.captureLegacyLyrics(SyncData(lyricOverrides = candidates))
        val references = candidates.map { it.copy(matchedLyric = null, matchedTranslatedLyric = null,
            matchedRomanizedLyric = null, originalLyric = null, originalTranslatedLyric = null,
            originalRomanizedLyric = null, lyricSyncEdited = false) }
        val published = writer.prepare(SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = references)))).use {
            it.content to it.objects.associate { obj -> obj.path to obj.content }
        }
        val recovery = MemoryRecovery().apply { optimize = true }
        val restored = SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        assertEquals(candidates.toSet(), recovery.preservedLyrics().toSet())
        assertEquals(candidates.size, recovery.preservedLyrics().size)
        assertEquals(candidates.size, restored.lyricOverrides.size)
        for (candidate in candidates) {
            val expected = candidate.copy(lyricSyncEdited = true, lyricSyncRevision = 1)
            assertEquals(expected, restored.lyricOverrides.single { it.id == candidate.id })
            assertEquals(expected, restored.playlists.single().songs.single { it.id == candidate.id })
        }
    }

    private fun assertPreserved(data: SyncData) {
        val song = data.playlists.single().songs.single()
        assertEquals(legacy.matchedLyric, song.matchedLyric)
        assertEquals(legacy.matchedTranslatedLyric, song.matchedTranslatedLyric)
        assertEquals(legacy.matchedRomanizedLyric, song.matchedRomanizedLyric)
        assertEquals(legacy.originalLyric, song.originalLyric)
        assertEquals(legacy.originalTranslatedLyric, song.originalTranslatedLyric)
        assertEquals(legacy.originalRomanizedLyric, song.originalRomanizedLyric)
        assertEquals(true, song.lyricSyncEdited)
        assertEquals(1L, song.lyricSyncRevision)
        assertEquals(song, data.lyricOverrides.single())
    }

    private fun publishOmitted(candidate: SyncSong = legacy, protocol: Int = 4): Pair<ByteArray, Map<String, ByteArray>> {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(SyncData(lyricOverrides = listOf(candidate)))
        val main = SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = listOf(omitted))))
        val archive = if (protocol == 3) writer.prepareOriginal(main) else writer.prepare(main)
        return archive.use { prepared ->
            assertEquals(protocol, SyncArchiveRepository.protocolVersion(prepared.content))
            prepared.content to prepared.objects.associate { it.path to it.content }
        }
    }

    private class MemoryRecovery : SyncLegacyLyricRecovery {
        private val completed = mutableSetOf<String>()
        private val retained = mutableListOf<SyncSong>()
        var recoveries = 0
            private set
        var optimize = false
        var readFailure: IOException? = null
        override fun isCompleted(sourceHash: String): Boolean = sourceHash in completed
        override fun recover(sourceHash: String, data: SyncData) {
            retained += data.lyricOverrides
            completed += sourceHash
            recoveries++
        }
        override fun preservedLyrics(): List<SyncSong> {
            readFailure?.let { throw it }
            return retained.toList()
        }
        override fun optimizeLegacyLyrics(): Boolean = optimize
    }
}
