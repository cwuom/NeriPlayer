package moe.ouom.neriplayer.core.download.storage.migration.plan

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadMigrationTargetMetadataIndexTest {
    @Test
    fun `target metadata is indexed only from readable and parseable sidecars`() {
        val metadata = DownloadedAudioMetadata(stableKey = "a", audioFileName = "a.flac")
        val readNames = mutableListOf<String>()

        val index = ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = listOf(
                entry("a.flac"),
                entry("a.flac.npmeta.json"),
                entry("b.flac.npmeta.json"),
                entry("c.flac.npmeta.json")
            ),
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            readText = { entry ->
                readNames += entry.name
                when (entry.name) {
                    "a.flac.npmeta.json" -> "valid"
                    "c.flac.npmeta.json" -> "broken"
                    else -> null
                }
            },
            parseMetadata = { text -> metadata.takeIf { text == "valid" } }
        )

        assertEquals(mapOf("a.flac" to metadata), index.metadataByAudioName)
        assertEquals(
            listOf("a.flac.npmeta.json", "b.flac.npmeta.json", "c.flac.npmeta.json"),
            readNames
        )
    }

    @Test
    fun `preparsed metadata wins and a missing parser skips sidecar reads`() {
        val preparsed = mapOf("a.flac" to DownloadedAudioMetadata(stableKey = "preparsed"))
        val rootEntries = listOf(entry("a.flac"), entry("a.flac.npmeta.json"))

        val withPreparsed = ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = rootEntries,
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            readText = { error("preparsed metadata must not read sidecars") },
            parseMetadata = { error("preparsed metadata must not parse sidecars") },
            parsedMetadataByAudioName = preparsed
        )
        val withoutParser = ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = rootEntries,
            coverEntries = emptyList(),
            lyricEntries = emptyList(),
            readText = { error("sidecars are not read without a parser") }
        )

        assertEquals(preparsed, withPreparsed.metadataByAudioName)
        assertEquals(emptyMap<String, DownloadedAudioMetadata>(), withoutParser.metadataByAudioName)
    }

    private fun entry(name: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "/music/NeriPlayer/$name",
        mediaUri = "file:///music/NeriPlayer/$name",
        localFilePath = "/music/NeriPlayer/$name",
        sizeBytes = 32L,
        lastModifiedMs = 1L,
        isDirectory = false
    )
}
