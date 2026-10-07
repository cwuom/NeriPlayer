package moe.ouom.neriplayer.core.download.storage.migration.plan

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadMigrationEntryCollectionTest {
    @Test
    fun `a root without managed audio or metadata collects nothing`() {
        assertEquals(
            emptyList<ManagedMigrationEntry>(),
            collect(rootEntries = listOf(entry("notes.txt")))
        )
    }

    @Test
    fun `metadata residue keeps only its own cover and lyric sidecars`() {
        val metadata = entry("Artist - Song.mp3.npmeta.json")
        val cover = entry("Artist - Song.jpg")
        val lyric = entry("Artist - Song.lrc")

        val entries = collect(
            rootEntries = listOf(metadata),
            coverEntries = listOf(entry("Other - Track.jpg"), cover),
            lyricEntries = listOf(lyric, entry("Other - Track.lrc"))
        )

        assertEquals(
            listOf(null to metadata, "Covers" to cover, "Lyrics" to lyric),
            entries.map { it.subdirectory to it.entry }
        )
    }

    @Test
    fun `managed audio and its metadata carry the parsed metadata`() {
        val audio = entry("Artist - Song.mp3")
        val metadata = entry("Artist - Song.mp3.npmeta.json")
        val parsed = DownloadedAudioMetadata(stableKey = "stable-song")

        val entries = collect(
            rootEntries = listOf(metadata, audio),
            parsedMetadataByAudioName = mapOf(audio.name to parsed)
        )

        assertEquals(
            listOf(audio to parsed, metadata to parsed),
            entries.map { it.entry to it.metadata }
        )
    }

    private fun collect(
        rootEntries: List<ManagedDownloadStorage.StoredEntry>,
        coverEntries: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        lyricEntries: List<ManagedDownloadStorage.StoredEntry> = emptyList(),
        parsedMetadataByAudioName: Map<String, DownloadedAudioMetadata> = emptyMap()
    ) = ManagedDownloadMigrationEntryCollector.collect(
        rootEntries = rootEntries,
        coverEntries = coverEntries,
        lyricEntries = lyricEntries,
        parsedMetadataByAudioName = parsedMetadataByAudioName,
        allowMetadataLessAudio = false
    )

    private fun entry(name: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "/old/$name",
        mediaUri = "file:///old/$name",
        localFilePath = "/old/$name",
        sizeBytes = 1L,
        lastModifiedMs = 100L
    )
}
