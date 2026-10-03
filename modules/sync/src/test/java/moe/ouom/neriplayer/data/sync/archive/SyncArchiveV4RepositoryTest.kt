package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Bridge
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Format

class SyncArchiveV4RepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val old = SyncData(playlists = listOf(SyncPlaylist(id = 1, songs = listOf(
        SyncSong(id = 7, album = "Netease", matchedLyric = "[00:01.000]synthetic text\n", originalLyric = "")
    ))))

    @Test fun everyPublishedPathIsAV4ObjectAndAnUnchangedReadCanRepublishTheSameClosure() = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(old)
        writer.prepare(SyncSongLyricMergePolicy.prepareLegacy(old)).use { prepared ->
            val remote = prepared.objects.associate { it.path to it.content }
            assertEquals(remote.keys, prepared.paths)
            assertTrue(prepared.paths.all { it.startsWith("neriplayer-sync-v4-") })
            val reader = SyncArchiveRepository(temporary.newFolder())
            val data = reader.read(prepared.content, verifyRemoteObjects = true) { Result.success(remote.getValue(it)) }.getOrThrow()
            assertEquals(prepared.paths, reader.lastReferencedPaths)
            reader.prepare(data).use { repeated ->
                assertTrue(prepared.content.contentEquals(repeated.content))
                assertEquals(prepared.paths, repeated.paths)
                assertFalse(repeated.objects(prepared.paths).any())
            }
        }
    }

    @Test fun originalStreamChecksumFailureCannotReachNormalizationOrRecovery() = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(old)
        writer.prepare(SyncSongLyricMergePolicy.prepareLegacy(old)).use { prepared ->
            val remote = prepared.objects.associate { it.path to it.content }
            val manifest = SyncArchiveV4Format.readManifest(prepared.content)
            val damaged = SyncArchiveV4Format.manifest(manifest.copy(mainRawHash = "0".repeat(64)))
            var normalized = 0
            val directory = temporary.newFolder()
            val reader = SyncArchiveRepository(directory) { normalized++ }
            assertTrue(reader.read(damaged) { Result.success(remote.getValue(it)) }.isFailure)
            assertEquals(0, normalized)
            assertTrue(reader.lastReferencedPaths.isEmpty())
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("sync-stage-") })
        }
    }

    @Test fun validCachedObjectsCannotConcealRemoteDeletion() = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(old)
        writer.prepare(SyncSongLyricMergePolicy.prepareLegacy(old)).use { prepared ->
            val remote = prepared.objects.associate { it.path to it.content }
            val reader = SyncArchiveRepository(temporary.newFolder())
            reader.read(prepared.content) { Result.success(remote.getValue(it)) }.getOrThrow()
            val deleted = IOException("synthetic remote deletion")
            val result = reader.read(prepared.content, verifyRemoteObjects = true) { Result.failure(deleted) }
            assertSame(deleted, result.exceptionOrNull())
            assertTrue(reader.lastReferencedPaths.isEmpty())
        }
    }

    @Test fun corruptRetainedRawSourceCannotBeSignedIntoANewValidArchive() = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(old)
        writer.prepare(SyncSongLyricMergePolicy.prepareLegacy(old)).use { prepared ->
            val remote = prepared.objects.associate { it.path to it.content }
            val directory = temporary.newFolder()
            val reader = SyncArchiveRepository(directory)
            val data = reader.read(prepared.content, { Result.success(remote.getValue(it)) }).getOrThrow()
            val file = File(directory, "legacy-source.raw")
            file.writeBytes(file.readBytes().also { bytes ->
                val character = bytes.indexOf('s'.code.toByte())
                assertTrue(character >= 0)
                bytes[character] = (bytes[character].toInt() xor 1).toByte()
            })
            assertThrows(IllegalArgumentException::class.java) { reader.prepare(data).close() }
            reader.read(prepared.content) { Result.success(remote.getValue(it)) }.getOrThrow()
            reader.prepare(data).close()
        }
    }

    @Test fun closingThePublishedArchiveDoesNotProtectUnpublishedIntermediateV3Objects() {
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val intermediate = File(directory, "neriplayer-sync-v3-${"a".repeat(64)}.zst")
        RandomAccessFile(intermediate, "rw").use { it.setLength(SyncArchiveLimits.CACHE_BYTES + 1L) }
        intermediate.setLastModified(1)
        val original = SyncPreparedArchive(SyncArchiveCodec.manifest(
            SyncArchiveManifest(3, SyncData(), null, 0, 0, 0)), setOf(intermediate.name), { emptySequence() }, cache::trim)
        SyncArchiveV4Bridge(directory, cache).prepare(original, null, null, emptySet()) {}.use { prepared ->
            assertTrue(prepared.paths.none { it == intermediate.name })
            prepared.objects.forEach { assertTrue(it.content.isNotEmpty()) }
        }
        assertFalse("unpublished intermediate objects must be eligible for cache eviction", intermediate.exists())
    }

    @Test fun failedRemoteReadTrimsPartialObjectsAndPreservesTheLastCompleteArchive() {
        verifyFailedReadCleanup(IOException("synthetic incomplete archive"))
    }

    @Test fun cancelledRemoteReadTrimsPartialObjectsAndPreservesTheOriginalCancellation() {
        verifyFailedReadCleanup(CancellationException("synthetic cancelled download"))
    }

    private fun verifyFailedReadCleanup(failure: Exception) = runBlocking {
        val writer = SyncArchiveRepository(temporary.newFolder())
        writer.captureLegacyLyrics(old)
        writer.prepare(SyncSongLyricMergePolicy.prepareLegacy(old)).use { prepared ->
            val remote = prepared.objects.associate { it.path to it.content }
            val directory = temporary.newFolder()
            val reader = SyncArchiveRepository(directory)
            val data = reader.read(prepared.content) { Result.success(remote.getValue(it)) }.getOrThrow()
            val partial = File(directory, "failed-download.zst")
            var fetched = 0
            val fetch: suspend (String) -> Result<ByteArray> = { path ->
                if (++fetched == 2) {
                    RandomAccessFile(partial, "rw").use { it.setLength(SyncArchiveLimits.CACHE_BYTES + 1L) }
                    partial.setLastModified(1)
                    if (failure is CancellationException) throw failure
                    Result.failure(failure)
                } else Result.success(remote.getValue(path))
            }
            if (failure is CancellationException) {
                val thrown = assertThrows(CancellationException::class.java) {
                    runBlocking { reader.read(prepared.content, true, fetch) }
                }
                assertSame(failure, thrown)
            } else assertSame(failure, reader.read(prepared.content, true, fetch).exceptionOrNull())
            assertTrue(fetched >= 2)
            assertFalse(partial.exists())
            assertTrue(reader.lastReferencedPaths.isEmpty())
            assertEquals(data, reader.read(prepared.content) { error("last complete archive was evicted") }.getOrThrow())
        }
    }
}
