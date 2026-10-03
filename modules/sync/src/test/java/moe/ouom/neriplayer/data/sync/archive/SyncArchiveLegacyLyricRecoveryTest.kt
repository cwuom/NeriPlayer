@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveLegacyLyricRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val legacy = SyncSong(id = 1, album = "netease", matchedLyric = "old unknown text", matchedLyricSource = "CLOUD_MUSIC",
        lyricSyncRevision = 999, originalRomanizedLyric = "old romanized baseline")

    @Test
    fun `complete archives retain raw unknown lyrics before both list and disk normalization`() = runTest {
        val (content, objects) = archive(frames(), 3)
        for (disk in listOf(false, true)) {
            val preferences = MemorySyncPreferences()
            val directory = temporary.newFolder()
            val storage = SecureTokenStorage(preferences.preferences, directory)
            val reader = SyncArchiveRepository(temporary.newFolder(), storage::retainLegacyLyrics)
            val data = if (disk) reader.readDataset(content, FileSyncPlaybackDatasetStore(temporary.newFolder()), { it }, { it }) {
                Result.success(objects.getValue(it))
            }.getOrThrow().use { it.data } else reader.read(content) { Result.success(objects.getValue(it)) }.getOrThrow()
            assertEquals(legacy.matchedLyric, data.playlists.single().songs.single().matchedLyric)
            assertEquals(legacy.originalRomanizedLyric, data.lyricOverrides.single().originalRomanizedLyric)
            assertEquals(1L, data.lyricOverrides.single().lyricSyncRevision)
            val recovered = SecureTokenStorage(preferences.restart().preferences, directory).getLyricOverridesForIdentityKeys(setOf("1|netease|"))
            assertEquals(SyncSongLyricMergePolicy.prepareLegacy(legacy), recovered.single())
            assertEquals(legacy.copy(lyricSyncRevision = 0), storage.getLegacyLyricCandidates().single())
        }
    }

    @Test
    fun `invalid archive tails never reach recovery and failed recovery releases disk staging`() = runTest {
        for (trailing in listOf(false, true)) {
            var callbacks = 0
            val raw = frames().let { if (trailing) it + byteArrayOf(1) else it }
            val (content, objects) = archive(raw, if (trailing) 3 else 4)
            val staging = temporary.newFolder()
            val reader = SyncArchiveRepository(temporary.newFolder()) { callbacks++ }
            val result = reader.readDataset(content, FileSyncPlaybackDatasetStore(staging), { it }, { it }) {
                Result.success(objects.getValue(it))
            }
            assertTrue(result.isFailure)
            assertEquals(0, callbacks)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
        val (content, objects) = archive(frames(), 3)
        val staging = temporary.newFolder()
        val failure = IOException("recovery unavailable")
        val result = SyncArchiveRepository(temporary.newFolder()) { throw failure }
            .readDataset(content, FileSyncPlaybackDatasetStore(staging), { it }, { it }) { Result.success(objects.getValue(it)) }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    private fun frames(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            for ((kind, payload) in listOf(1 to ProtoBuf.encodeToByteArray(SyncPlaylist(id = 7)),
                2 to ProtoBuf.encodeToByteArray(legacy), 15 to ProtoBuf.encodeToByteArray(legacy))) {
                output.writeByte(kind)
                output.writeInt(payload.size)
                output.write(payload)
            }
        }
    }.toByteArray()

    private fun archive(raw: ByteArray, count: Long): Pair<ByteArray, Map<String, ByteArray>> {
        val cache = SyncArchiveCache(temporary.newFolder())
        val ref = cache.store(raw, index = false)
        return SyncArchiveCodec.manifest(SyncArchiveManifest(3, SyncData(lastModified = 1), ref, count, raw.size.toLong(), 1)) to
            mapOf(ref.path to cache.readCompressed(ref))
    }
}
