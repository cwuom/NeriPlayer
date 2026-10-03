package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveVersionMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun newArchivesCarryAnExplicitV4Envelope() {
        SyncArchiveRepository(temporary.newFolder()).prepare(SyncData(lastModified = 1)).use { prepared ->
            assertEquals("NPSYNC04", String(prepared.content, 0, 8, Charsets.US_ASCII))
        }
    }

    @Test fun frozenV3AndGzipFixturePreserveAllHistoricalLyricVariants() = verifyFrozenArchive("v3-frozen")

    @Test fun frozenV4FixturePreservesTheSchemaAndEveryHistoricalLyricVariant() = verifyFrozenArchive("v4-frozen")

    private fun verifyFrozenArchive(name: String) = runBlocking {
        val fixture = File(checkNotNull(javaClass.getResource("/sync/archive/$name")).toURI())
        val oldFixture = File(checkNotNull(javaClass.getResource("/sync/archive/v3-frozen")).toURI())
        val old = SyncDataSerializer.deserialize(File(oldFixture, "legacy.bin").readBytes())
        val expected = SyncSongLyricMergePolicy.prepareLegacy(old)
        val retained = mutableListOf<moe.ouom.neriplayer.data.model.sync.SyncSong>()
        val recovery = object : SyncLegacyLyricRecovery {
            override fun isCompleted(sourceHash: String) = false
            override fun recover(sourceHash: String, data: SyncData) { retained.addAll(data.lyricOverrides) }
        }
        val reader = SyncArchiveRepository(temporary.newFolder(), recovery)
        val restored = reader.read(File(fixture, SyncArchiveRepository.MANIFEST_FILE_NAME).readBytes()) { path ->
            Result.success(File(fixture, path).readBytes())
        }.getOrThrow()
        assertEquals(expected, restored)
        assertEquals(2, retained.size)
        assertEquals(2, retained.map { it.matchedLyric }.distinct().size)
        assertEquals("different historical metadata", restored.playbackStatBuckets.single().name)
    }
}
