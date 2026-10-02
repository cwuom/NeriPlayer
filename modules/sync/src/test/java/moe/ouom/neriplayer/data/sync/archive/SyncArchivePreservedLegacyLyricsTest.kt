package moe.ouom.neriplayer.data.sync.archive

import kotlinx.coroutines.test.runTest
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchivePreservedLegacyLyricsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy = SyncSong(id = 7, album = "Netease", matchedLyric = "old modified text",
        matchedTranslatedLyric = "old translation", originalLyric = "baseline text", originalTranslatedLyric = "baseline translation")
    private val omitted = legacy.copy(matchedLyric = null, matchedTranslatedLyric = null,
        originalLyric = null, originalTranslatedLyric = null, lyricSyncEdited = false)

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
    fun `completed receipt restores lyrics from durable candidates without downloading source again`() = runTest {
        val published = publishOmitted()
        val recovery = MemoryRecovery()
        SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        val source = requireNotNull(SyncArchiveCodec.readManifest(published.first).legacyLyrics)
        val downloaded = mutableListOf<String>()
        SyncArchiveRepository(temporary.newFolder(), recovery).readDataset(published.first,
            FileSyncPlaybackDatasetStore(temporary.newFolder()), { it }, { it }) { path ->
            downloaded += path
            Result.success(published.second.getValue(path))
        }.getOrThrow().use { dataset -> assertPreserved(dataset.data) }
        assertFalse(source.root.path in downloaded)
        assertEquals(1, recovery.recoveries)
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
        assertNotNull(SyncArchiveCodec.readManifest(published.first).legacyLyrics)
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
    fun `completed receipt read failure releases staging and remains retryable without source download`() = runTest {
        val published = publishOmitted()
        val recovery = MemoryRecovery()
        SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        val source = requireNotNull(SyncArchiveCodec.readManifest(published.first).legacyLyrics)
        val failure = IOException("durable candidate read failed")
        recovery.readFailure = failure
        val staging = temporary.newFolder()
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val downloaded = mutableListOf<String>()
        val result = reader.readDataset(published.first, FileSyncPlaybackDatasetStore(staging), { it }, { it }) { path ->
            downloaded += path
            Result.success(published.second.getValue(path))
        }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertFalse(source.root.path in downloaded)
        recovery.readFailure = null
        reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow().also(::assertPreserved)
        assertEquals(1, recovery.recoveries)
    }

    @Test
    fun `optional optimization omits unknown lyrics from projection while preserving immutable source`() = runTest {
        val published = publishOmitted()
        val recovery = MemoryRecovery().apply { optimize = true }
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val restored = reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow()
        assertTrue(restored.lyricOverrides.isEmpty())
        assertEquals(omitted, restored.playlists.single().songs.single())
        assertEquals(listOf(legacy), recovery.preservedLyrics())
        reader.prepare(restored).use { prepared ->
            assertEquals(SyncArchiveCodec.readManifest(published.first).legacyLyrics,
                SyncArchiveCodec.readManifest(prepared.content).legacyLyrics)
        }
    }

    @Test
    fun `optimized migration still captures and restores unknown Bilibili lyrics`() = runTest {
        val bilibili = legacy.copy(id = 8, album = "Bilibili")
        val youtube = legacy.copy(id = 9, album = "YouTube", channelId = "youtube")
        val candidates = listOf(legacy, bilibili, youtube)
        val writer = SyncArchiveRepository(temporary.newFolder(), MemoryRecovery().apply { optimize = true })
        writer.captureLegacyLyrics(SyncData(lyricOverrides = candidates))
        val references = candidates.map { it.copy(matchedLyric = null, matchedTranslatedLyric = null,
            originalLyric = null, originalTranslatedLyric = null, lyricSyncEdited = false) }
        val published = writer.prepare(SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = references)))).use {
            it.content to it.objects.associate { obj -> obj.path to obj.content }
        }
        val recovery = MemoryRecovery().apply { optimize = true }
        val restored = SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        assertEquals(listOf(bilibili), recovery.preservedLyrics())
        assertEquals(bilibili.matchedLyric, restored.lyricOverrides.single().matchedLyric)
        assertEquals(bilibili.originalLyric, restored.lyricOverrides.single().originalLyric)
        assertEquals(bilibili.id, restored.lyricOverrides.single().id)
        assertEquals(true, restored.lyricOverrides.single().lyricSyncEdited)
        assertTrue(restored.playlists.single().songs.filter { it.id != bilibili.id }.all { it.matchedLyric == null })
    }

    private fun assertPreserved(data: SyncData) {
        val song = data.playlists.single().songs.single()
        assertEquals(legacy.matchedLyric, song.matchedLyric)
        assertEquals(legacy.matchedTranslatedLyric, song.matchedTranslatedLyric)
        assertEquals(legacy.originalLyric, song.originalLyric)
        assertEquals(legacy.originalTranslatedLyric, song.originalTranslatedLyric)
        assertEquals(true, song.lyricSyncEdited)
        assertEquals(1L, song.lyricSyncRevision)
        assertEquals(song.matchedLyric, data.lyricOverrides.single().matchedLyric)
        assertEquals(song.originalLyric, data.lyricOverrides.single().originalLyric)
    }

    private fun publishOmitted(candidate: SyncSong = legacy): Pair<ByteArray, Map<String, ByteArray>> {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(SyncData(lyricOverrides = listOf(candidate)))
        val main = SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = listOf(omitted))))
        return writer.prepare(main).use { prepared ->
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
