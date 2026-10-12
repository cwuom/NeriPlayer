package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.runtime.dataset.readForTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveRemoteCacheVerifierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun approvedRemoteCacheReadsEverySectionWithoutFetchingOrSavingAgain() = runBlocking {
        for (protocol in listOf(3, 4)) {
            val fixture = fixture(protocol)
            val pointerTimes = fixture.cache.listFiles().orEmpty()
                .filter { it.name.endsWith(".ref") || it.name.endsWith(".v4ref") }
                .associateWith { pointer ->
                    assertTrue(pointer.setLastModified(42_000L))
                    pointer.lastModified()
                }
            assertFalse(pointerTimes.isEmpty())
            val verified = ConcurrentHashMap.newKeySet<String>()
            val approvals = AtomicInteger()
            val result = fixture.reader.read(fixture.content, verifyRemoteObjects = true,
                cachedRemoteVerifier = { path, compressed ->
                    assertArrayEquals(fixture.objects.getValue(path), compressed)
                    verified += path
                    approvals.incrementAndGet()
                    true
                }) { error("approved cache must not fetch $it") }.getOrThrow()
            assertEquals(fixture.expected, result)
            assertEquals(fixture.objects.keys, verified)
            assertEquals(verified.size, approvals.get())
            assertEquals(fixture.objects.keys, fixture.reader.lastReferencedPaths)
            pointerTimes.forEach { (pointer, modified) -> assertEquals(modified, pointer.lastModified()) }
            assertNoWorkspace(fixture.cache)
        }
    }

    @Test fun datasetReadUsesProofWithoutDroppingPreservedLyricsHistoryOrStatistics() = runBlocking {
        for (protocol in listOf(3, 4)) {
            val fixture = fixture(protocol)
            val staging = temporary.newFolder()
            val verified = ConcurrentHashMap.newKeySet<String>()
            fixture.reader.readDataset(fixture.content, FileSyncPlaybackDatasetStore(staging), { it }, { it },
                verifyRemoteObjects = true, cachedRemoteVerifier = { path, _ -> verified.add(path); true }) {
                error("approved dataset cache must not fetch $it")
            }.getOrThrow().use { dataset ->
                assertTrue(dataset.data.playbackStats.isEmpty())
                assertTrue(dataset.data.playbackStatBuckets.isEmpty())
                assertEquals(fixture.expected, dataset.readForTest())
                val restored = dataset.data.playlists.single().songs.single()
                assertEquals(legacy.copy(lyricSyncEdited = true, lyricSyncRevision = 1), restored)
                assertEquals(listOf(restored), dataset.data.lyricOverrides)
            }
            assertEquals(fixture.objects.keys, verified)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertNoWorkspace(fixture.cache)
        }
    }

    @Test fun rejectedOrAbsentProofStillFetchesTheCompleteRemoteClosure() = runBlocking {
        for (protocol in listOf(3, 4)) {
            val fixture = fixture(protocol)
            for (reject in listOf(false, true)) {
                val fetched = ConcurrentHashMap.newKeySet<String>()
                val verified = ConcurrentHashMap.newKeySet<String>()
                val fetch: suspend (String) -> Result<ByteArray> = { path ->
                    fetched += path
                    Result.success(fixture.objects.getValue(path))
                }
                val result = if (reject) fixture.reader.read(fixture.content, verifyRemoteObjects = true,
                    cachedRemoteVerifier = { path, _ -> verified.add(path); false }, fetch = fetch)
                else fixture.reader.read(fixture.content, true, fetch)
                assertEquals(fixture.expected, result.getOrThrow())
                assertEquals(fixture.objects.keys, fetched)
                assertEquals(if (reject) fixture.objects.keys else emptySet<String>(), verified)
            }
        }
    }

    @Test fun missingWrongSizedAndCorruptCachesCannotReachTheRemoteVerifier() = runBlocking {
        for (protocol in listOf(3, 4)) {
            for (damage in 0..2) {
                val fixture = fixture(protocol)
                val damagedPath = fixture.objects.keys.first()
                val file = File(fixture.cache, damagedPath)
                when (damage) {
                    0 -> assertTrue(file.delete())
                    1 -> file.appendBytes(byteArrayOf(0))
                    else -> file.writeBytes(file.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() })
                }
                val verified = ConcurrentHashMap.newKeySet<String>()
                val fetched = ConcurrentHashMap.newKeySet<String>()
                val restored = fixture.reader.read(fixture.content, verifyRemoteObjects = true,
                    cachedRemoteVerifier = { path, _ -> verified.add(path); true }) { path ->
                    fetched += path
                    Result.success(fixture.objects.getValue(path))
                }.getOrThrow()
                assertEquals(fixture.expected, restored)
                assertFalse(damagedPath in verified)
                assertEquals(setOf(damagedPath), fetched)
                assertArrayEquals(fixture.objects.getValue(damagedPath), file.readBytes())
            }
        }
    }

    @Test fun remoteVerifierMissingFailureKeepsItsTypeAndOriginalInstance() = runBlocking {
        for (protocol in listOf(3, 4)) {
            val fixture = fixture(protocol)
            val failure = MissingRemoteObject("missing at fixed remote version")
            val result = fixture.reader.read(fixture.content, verifyRemoteObjects = true,
                cachedRemoteVerifier = { _, _ -> throw failure }) { error("proof failure must not fetch") }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(fixture.reader.lastReferencedPaths.isEmpty())
            assertNoWorkspace(fixture.cache)
        }
    }

    @Test fun remoteVerifierCancellationEscapesAsTheOriginalInstance() = runBlocking {
        for (protocol in listOf(3, 4)) {
            val fixture = fixture(protocol)
            val cancellation = CancellationException("remote cache proof cancelled")
            val failure = runCatching {
                fixture.reader.read(fixture.content, verifyRemoteObjects = true,
                    cachedRemoteVerifier = { _, _ -> throw cancellation }) { error("cancelled proof must not fetch") }
            }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(fixture.reader.lastReferencedPaths.isEmpty())
            assertNoWorkspace(fixture.cache)
        }
    }

    @Test fun approvedCompressedCacheStillRejectsForgedRawChecksums() = runBlocking {
        for (protocol in listOf(3, 4)) {
            val fixture = fixture(protocol)
            val content = if (protocol == 3) {
                val manifest = SyncArchiveCodec.readManifest(fixture.content)
                SyncArchiveCodec.manifest(manifest.copy(root = requireNotNull(manifest.root).copy(rawHash = "0".repeat(64))))
            } else {
                val manifest = SyncArchiveV4Format.readManifest(fixture.content)
                SyncArchiveV4Format.manifest(manifest.copy(main = manifest.main.copy(
                    root = requireNotNull(manifest.main.root).copy(rawHash = "0".repeat(64)))))
            }
            assertApprovedCacheFailure(fixture, content)
        }
    }

    @Test fun approvedCacheStillRejectsDeclaredTreeBudgetViolations() = runBlocking {
        for (protocol in listOf(3, 4)) {
            val fixture = fixture(protocol)
            val content = if (protocol == 3) {
                val manifest = SyncArchiveCodec.readManifest(fixture.content)
                SyncArchiveCodec.manifest(manifest.copy(rawDataBytes = manifest.rawDataBytes - 1))
            } else {
                val manifest = SyncArchiveV4Format.readManifest(fixture.content)
                SyncArchiveV4Format.manifest(manifest.copy(main = manifest.main.copy(rawBytes = manifest.main.rawBytes - 1)))
            }
            assertApprovedCacheFailure(fixture, content)
        }
    }

    @Test fun approvedV4ObjectsStillRequireTheCompleteUnpackedStreamChecksum() = runBlocking {
        val fixture = fixture(4)
        val manifest = SyncArchiveV4Format.readManifest(fixture.content)
        assertApprovedCacheFailure(fixture, SyncArchiveV4Format.manifest(manifest.copy(mainRawHash = "0".repeat(64))))
    }

    private suspend fun assertApprovedCacheFailure(fixture: Fixture, content: ByteArray) {
        val staging = temporary.newFolder()
        val fetches = AtomicInteger()
        val normalized = AtomicInteger()
        val reader = SyncArchiveRepository(fixture.cache) { normalized.incrementAndGet() }
        val result = reader.readDataset(content, FileSyncPlaybackDatasetStore(staging), { it }, { it },
            verifyRemoteObjects = true, cachedRemoteVerifier = { _, _ -> true }) {
            fetches.incrementAndGet()
            error("approved cache validation must not fetch")
        }
        assertTrue(result.isFailure)
        assertEquals(0, fetches.get())
        assertEquals(0, normalized.get())
        assertTrue(reader.lastReferencedPaths.isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertNoWorkspace(fixture.cache)
    }

    private suspend fun fixture(protocol: Int): Fixture {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(SyncData(lastModified = 1, lyricOverrides = listOf(legacy)))
        val omitted = legacy.copy(matchedLyric = null, matchedTranslatedLyric = null, matchedRomanizedLyric = null,
            originalLyric = null, originalTranslatedLyric = null, originalRomanizedLyric = null, lyricSyncEdited = false)
        val input = SyncData(lastModified = 2, playlists = listOf(SyncPlaylist(id = 1, songs = listOf(omitted))),
            recentPlays = listOf(SyncRecentPlay(songId = omitted.id, song = omitted, playedAt = 7, resumePositionMs = 1234)),
            playbackStats = listOf(SyncTrackStat(identityKey = "track", id = omitted.id, playCount = 3, totalListenMs = 9876)),
            playbackStatBuckets = listOf(SyncPlaybackStatBucket(identityKey = "track", dayStartAt = 5, playCount = 2)))
        val (content, objects) = (if (protocol == 3) writer.prepareOriginal(input) else writer.prepare(input)).use {
            it.content to it.objects.associate { obj -> obj.path to obj.content }
        }
        val cache = temporary.newFolder()
        val reader = SyncArchiveRepository(cache)
        val expected = reader.read(content) { Result.success(objects.getValue(it)) }.getOrThrow()
        assertEquals(protocol, SyncArchiveRepository.protocolVersion(content))
        assertEquals(objects.keys, reader.lastReferencedPaths)
        return Fixture(content, objects, cache, reader, expected)
    }

    private fun assertNoWorkspace(cache: File) {
        assertTrue(cache.listFiles().orEmpty().none { it.name.startsWith("sync-stage-") && it.isDirectory })
    }

    private data class Fixture(val content: ByteArray, val objects: Map<String, ByteArray>, val cache: File,
        val reader: SyncArchiveRepository, val expected: SyncData)

    private class MissingRemoteObject(message: String) : IOException(message)

    private val legacy = SyncSong(id = 7, album = "Netease", matchedLyric = "old text\r\n",
        matchedTranslatedLyric = "old translation", matchedRomanizedLyric = "",
        originalLyric = "baseline", originalTranslatedLyric = "baseline translation", originalRomanizedLyric = "base romanized")
}
