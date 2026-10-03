package moe.ouom.neriplayer.data.sync.archive

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncFavoritePlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveLegacyDeviceMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy = SyncSong(id = 7, album = "netease", matchedLyric = "old user lyrics",
        matchedTranslatedLyric = "old translation", matchedRomanizedLyric = "old romanized")
    private val oldData = SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = listOf(legacy))))

    @Test
    fun `a second device automatically retains unknown lyrics from the migrated cloud archive`() = runTest {
        val firstDevice = SyncArchiveRepository(temporary.newFolder())
        firstDevice.captureLegacyLyrics(oldData)
        val normalized = SyncSongLyricMergePolicy.converge(oldData)
        val published = firstDevice.prepare(normalized).use { prepared ->
            prepared.content to prepared.objects.associate { it.path to it.content }
        }
        val retained = mutableListOf<SyncSong>()
        val secondDevice = SyncArchiveRepository(temporary.newFolder()) { data ->
            retained += data.lyricOverrides.filter { it.lyricSyncEdited == null }
        }
        val received = secondDevice.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow()

        assertEquals(legacy.matchedLyric, received.playlists.single().songs.single().matchedLyric)
        assertEquals(listOf(SyncSongLyricMergePolicy.prepareLegacy(legacy)), received.lyricOverrides)
        assertEquals(listOf(legacy), retained)
    }

    @Test
    fun `completed V3 and V4 recovery still loads the full source for a lossless V4 republication`() = runTest {
        for (protocol in listOf(3, 4)) {
            val published = publish(protocol = protocol)
            val recovery = MemoryRecovery()
            val first = SyncArchiveRepository(temporary.newFolder(), recovery)
            first.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow()
            assertEquals(listOf(legacy), recovery.data.single().lyricOverrides)
            val reopened = SyncArchiveRepository(temporary.newFolder(), recovery)
            val requested = mutableSetOf<String>()
            reopened.read(published.first) { path ->
                requested += path
                Result.success(published.second.getValue(path))
            }.getOrThrow()
            val source = requireNotNull(SyncArchiveRepository.originalManifest(published.first).legacyLyrics)
            assertEquals(published.second.keys, requested)
            assertEquals(1, recovery.data.size)
            reopened.prepare(SyncData()).use { prepared ->
                assertEquals(4, SyncArchiveRepository.protocolVersion(prepared.content))
                assertEquals(source, SyncArchiveRepository.originalManifest(prepared.content).legacyLyrics)
                val objects = prepared.objects.associate { it.path to it.content }
                val nextRecovery = MemoryRecovery()
                val next = SyncArchiveRepository(temporary.newFolder(), nextRecovery).read(prepared.content) {
                    Result.success(objects.getValue(it))
                }.getOrThrow()
                assertEquals(listOf(legacy), nextRecovery.data.single().lyricOverrides)
                assertEquals(listOf(SyncSongLyricMergePolicy.prepareLegacy(legacy)), next.lyricOverrides)
            }
        }
    }

    @Test
    fun `subsequent publications preserve the fixed source without republishing its objects`() = runTest {
        val published = publish()
        val reader = SyncArchiveRepository(temporary.newFolder(), MemoryRecovery())
        val data = reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow()
        reader.prepare(data.copy(lastModified = 100)).use { prepared ->
            assertEquals(SyncArchiveRepository.originalManifest(published.first).legacyLyrics,
                SyncArchiveRepository.originalManifest(prepared.content).legacyLyrics)
            val unchanged = prepared.objects(published.second.keys).toList()
            assertTrue(unchanged.isEmpty())
        }
    }

    @Test
    fun `different recovery targets independently receive the fixed source`() = runTest {
        val published = publish()
        val firstTarget = MemoryRecovery()
        val secondTarget = MemoryRecovery()
        for (target in listOf(firstTarget, secondTarget)) {
            SyncArchiveRepository(temporary.newFolder(), target).read(published.first) {
                Result.success(published.second.getValue(it))
            }.getOrThrow()
            assertEquals(listOf(legacy), target.data.single().lyricOverrides)
        }
    }

    @Test
    fun `only unknown lyric candidates are archived and all text variants survive`() = runTest {
        val candidate = legacy.copy(originalLyric = "base", originalTranslatedLyric = "base translation",
            originalRomanizedLyric = "base romanized", lyricSyncRevision = 99)
        val input = oldData.copy(lyricOverrides = listOf(candidate, candidate,
            legacy.copy(id = 8, lyricSyncEdited = true, lyricSyncRevision = 20),
            legacy.copy(id = 9, lyricSyncEdited = false)))
        val published = publish(input)
        val recovery = MemoryRecovery()
        SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        val retained = recovery.data.single().lyricOverrides
        assertEquals(listOf(candidate.copy(lyricSyncRevision = 0), legacy), retained)
        assertTrue(retained.all { it.lyricSyncEdited == null && it.lyricSyncRevision == 0L })
    }

    @Test
    fun `tampered and truncated source objects never mark recovery complete`() = runTest {
        val published = publish(protocol = 3)
        val source = requireNotNull(SyncArchiveRepository.originalManifest(published.first).legacyLyrics)
        val original = published.second.getValue(source.root.path)
        for (broken in listOf(original.copyOf(original.size - 1), original.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })) {
            val recovery = MemoryRecovery()
            val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
            val result = reader.read(published.first) { path ->
                Result.success(if (path == source.root.path) broken else published.second.getValue(path))
            }
            assertTrue(result.isFailure)
            assertTrue(recovery.data.isEmpty())
            assertFalse(recovery.isCompleted(source.hash))
        }
    }

    @Test
    fun `wrong source record counts never reach recovery`() = runTest {
        val published = publish(protocol = 3)
        val manifest = SyncArchiveRepository.originalManifest(published.first)
        val source = requireNotNull(manifest.legacyLyrics)
        val content = SyncArchiveCodec.manifest(manifest.copy(legacyLyrics = source.copy(recordCount = source.recordCount + 1)))
        val recovery = MemoryRecovery()
        val result = SyncArchiveRepository(temporary.newFolder(), recovery).read(content) {
            Result.success(published.second.getValue(it))
        }
        assertTrue(result.isFailure)
        assertTrue(recovery.data.isEmpty())
    }

    @Test
    fun `durable recovery failure releases playback staging and remains retryable`() = runTest {
        val published = publish()
        val recovery = MemoryRecovery()
        val failure = IOException("legacy ledger failed")
        recovery.failure = failure
        val staging = temporary.newFolder()
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val result = reader.readDataset(published.first, FileSyncPlaybackDatasetStore(staging), { it }, { it }) {
            Result.success(published.second.getValue(it))
        }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertTrue(recovery.data.isEmpty())
        recovery.failure = null
        reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow()
        assertEquals(listOf(legacy), recovery.data.single().lyricOverrides)
    }

    @Test
    fun `cancelled source retrieval releases playback staging without a receipt`() = runTest {
        val published = publish(protocol = 3)
        val source = requireNotNull(SyncArchiveRepository.originalManifest(published.first).legacyLyrics)
        val recovery = MemoryRecovery()
        val staging = temporary.newFolder()
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        var cancelled = false
        try {
            reader.readDataset(published.first, FileSyncPlaybackDatasetStore(staging), { it }, { it }) { path ->
                if (path == source.root.path) throw CancellationException("cancel legacy retrieval")
                Result.success(published.second.getValue(path))
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertTrue(recovery.data.isEmpty())
    }

    @Test
    fun `legacy source cannot override a newer confirmed edit or reset in normal data`() = runTest {
        for (edited in listOf(true, false)) {
            val current = legacy.copy(matchedLyric = if (edited) "current edit" else null,
                matchedTranslatedLyric = null, matchedRomanizedLyric = null,
                lyricSyncEdited = edited, lyricSyncRevision = 20)
            val writer = SyncArchiveRepository(temporary.newFolder())
            writer.captureLegacyLyrics(oldData)
            val input = SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = listOf(current))), lyricOverrides = listOf(current))
            val published = writer.prepare(input).use { it.content to it.objects.associate { obj -> obj.path to obj.content } }
            val recovery = MemoryRecovery()
            val received = SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
                Result.success(published.second.getValue(it))
            }.getOrThrow()
            assertEquals(current, received.playlists.single().songs.single())
            assertEquals(listOf(legacy), recovery.data.single().lyricOverrides)
            assertTrue(received.lyricOverrides.all { it.lyricSyncRevision == 20L && it.lyricSyncEdited == edited })
        }
    }

    @Test
    fun `legacy source totals are rejected before fetching any objects`() {
        val published = publish(protocol = 3)
        val manifest = SyncArchiveRepository.originalManifest(published.first)
        val source = requireNotNull(manifest.legacyLyrics)
        val oversized = listOf(
            source.copy(rawDataBytes = 33L * 1024 * 1024),
            source.copy(chunkCount = 1025, rawDataBytes = 1024 * 1024)
        )
        for (invalid in oversized) {
            val content = SyncArchiveCodec.manifest(manifest.copy(legacyLyrics = invalid))
            assertThrows(IllegalArgumentException::class.java) { SyncArchiveCodec.readManifest(content) }
        }
    }

    @Test
    fun `capturing an oversized legacy record fails before creating source objects`() {
        val directory = temporary.newFolder()
        val repository = SyncArchiveRepository(directory)
        val oversized = SyncData(lyricOverrides = listOf(legacy.copy(matchedLyric = "x".repeat(32 * 1024 * 1024))))
        assertThrows(IOException::class.java) { repository.captureLegacyLyrics(oversized) }
        assertEquals(setOf(".sync-stage.guard"), directory.listFiles().orEmpty().map { it.name }.toSet())
    }

    @Test
    fun `new capture replaces the previous source and preserves unknown baseline only lyrics`() {
        val inputs = listOf(
            SyncData(),
            SyncData(lyricOverrides = listOf(legacy.copy(lyricSyncEdited = true, lyricSyncRevision = 20))),
            SyncData(lyricOverrides = listOf(legacy.copy(lyricSyncEdited = false))),
            SyncData(lyricOverrides = listOf(legacy.copy(matchedLyric = null, matchedTranslatedLyric = null,
                matchedRomanizedLyric = null, originalLyric = "ordinary baseline")))
        )
        for (input in inputs) {
            val repository = SyncArchiveRepository(temporary.newFolder())
            repository.captureLegacyLyrics(oldData)
            val previousPath = repository.prepare(SyncSongLyricMergePolicy.converge(oldData)).use {
                requireNotNull(SyncArchiveRepository.originalManifest(it.content).legacyLyrics).root.path
            }
            repository.captureLegacyLyrics(input)
            repository.prepare(SyncSongLyricMergePolicy.converge(input)).use { prepared ->
                if (input.lyricOverrides.singleOrNull()?.lyricSyncEdited == null && input.lyricOverrides.isNotEmpty()) {
                    assertTrue(SyncArchiveRepository.originalManifest(prepared.content).legacyLyrics != null)
                } else {
                    assertNull(SyncArchiveRepository.originalManifest(prepared.content).legacyLyrics)
                }
                assertFalse(prepared.objects.any { it.path == previousPath })
                assertFalse(previousPath in prepared.paths)
            }
        }
    }

    @Test
    fun `translation romanized and explicit empty legacy lyrics survive across all containers`() = runTest {
        val empty = legacy.copy(id = 10, matchedLyric = "", matchedTranslatedLyric = null, matchedRomanizedLyric = null)
        val translated = legacy.copy(id = 11, matchedLyric = null, matchedRomanizedLyric = null,
            originalTranslatedLyric = "translation baseline")
        val romanized = legacy.copy(id = 12, matchedLyric = null, matchedTranslatedLyric = null,
            originalRomanizedLyric = "romanized baseline")
        val input = SyncData(
            lyricOverrides = listOf(empty),
            playlists = listOf(SyncPlaylist(id = 1, songs = listOf(translated))),
            favoritePlaylists = listOf(SyncFavoritePlaylist(id = 2, source = "netease", songs = listOf(translated))),
            recentPlays = listOf(SyncRecentPlay(songId = romanized.id, song = romanized, playedAt = 100))
        )
        val published = publish(input)
        val recovery = MemoryRecovery()
        val received = SyncArchiveRepository(temporary.newFolder(), recovery).read(published.first) {
            Result.success(published.second.getValue(it))
        }.getOrThrow()
        assertEquals(setOf(empty, translated, romanized), recovery.data.single().lyricOverrides.toSet())
        assertEquals(3, recovery.data.single().lyricOverrides.size)
        assertEquals(3, received.lyricOverrides.size)
        assertTrue(received.lyricOverrides.all { it.lyricSyncEdited == true && it.lyricSyncRevision == 1L })
        assertEquals("", received.lyricOverrides.single { it.id == empty.id }.matchedLyric)
        assertEquals(translated.matchedTranslatedLyric,
            received.favoritePlaylists.single().songs.single().matchedTranslatedLyric)
        assertEquals(translated.originalTranslatedLyric,
            received.favoritePlaylists.single().songs.single().originalTranslatedLyric)
        assertEquals(romanized.matchedRomanizedLyric, received.recentPlays.single().song.matchedRomanizedLyric)
        assertEquals(romanized.originalRomanizedLyric, received.recentPlays.single().song.originalRomanizedLyric)
    }

    @Test
    fun `large legacy text spans content chunks and the migration index restores the complete text`() = runTest {
        val large = legacy.copy(matchedLyric = "long old text\n".repeat(160_000),
            matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized")
        val published = publish(SyncData(lyricOverrides = listOf(large)))
        val source = requireNotNull(SyncArchiveRepository.originalManifest(published.first).legacyLyrics)
        assertTrue(source.chunkCount > 1L)
        assertTrue(source.root.index)
        val recovery = MemoryRecovery()
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val received = reader.read(published.first) { Result.success(published.second.getValue(it)) }.getOrThrow()
        assertEquals(listOf(large), recovery.data.single().lyricOverrides)
        assertEquals(listOf(SyncSongLyricMergePolicy.prepareLegacy(large)), received.lyricOverrides)
        assertTrue(published.second.keys.all { it in reader.lastReferencedPaths })
    }

    private fun publish(data: SyncData = oldData, protocol: Int = 4): Pair<ByteArray, Map<String, ByteArray>> {
        val repository = SyncArchiveRepository(temporary.newFolder())
        repository.captureLegacyLyrics(data)
        val main = SyncSongLyricMergePolicy.converge(data)
        val archive = if (protocol == 3) repository.prepareOriginal(main) else repository.prepare(main)
        return archive.use { prepared ->
            assertEquals(protocol, SyncArchiveRepository.protocolVersion(prepared.content))
            prepared.content to prepared.objects.associate { it.path to it.content }
        }
    }

    private class MemoryRecovery : SyncLegacyLyricRecovery {
        private val completed = mutableSetOf<String>()
        val data = mutableListOf<SyncData>()
        var failure: IOException? = null
        override fun isCompleted(sourceHash: String): Boolean = sourceHash in completed
        override fun preservedLyrics(): List<SyncSong> = data.flatMap { it.lyricOverrides }
        override fun recover(sourceHash: String, data: SyncData) {
            failure?.let { throw it }
            this.data += data
            completed += sourceHash
        }
    }
}
