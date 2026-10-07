package moe.ouom.neriplayer.data.local.audioimport

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalSongCreationEvidenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `modification timestamp sources never count as creation evidence`() {
        listOf(" mtime ", "SAF_LAST_MODIFIED", "mediastore_date_added").forEach { source ->
            assertFalse(
                source,
                hasStableSongCreationEvidence(
                    song(
                        logicalCreatedAtMs = 10L,
                        createdAtSource = source,
                        createdAtConfidence = "EXACT",
                        addedAt = 10L
                    )
                )
            )
        }
    }

    @Test
    fun `a valid logical creation time is stable evidence regardless of confidence`() {
        assertTrue(
            hasStableSongCreationEvidence(
                song(logicalCreatedAtMs = 10L, createdAtSource = "EXIF", createdAtConfidence = "UNKNOWN")
            )
        )
    }

    @Test
    fun `exact or provider reported confidence is stable evidence without a logical time`() {
        assertTrue(hasStableSongCreationEvidence(song(logicalCreatedAtMs = 0L, createdAtConfidence = "provider_reported")))
        assertTrue(hasStableSongCreationEvidence(song(createdAtConfidence = "exact")))
        assertFalse(hasStableSongCreationEvidence(song(createdAtConfidence = "INFERRED", addedAt = 10L)))
        assertFalse(hasStableSongCreationEvidence(song(createdAtConfidence = "UNKNOWN", addedAt = 10L)))
    }

    @Test
    fun `legacy rows without confidence fall back to a positive added time`() {
        assertTrue(hasStableSongCreationEvidence(song(addedAt = 10L)))
        assertFalse(hasStableSongCreationEvidence(song(addedAt = 0L)))
    }

    @Test
    fun `creation confidence ranks exact above provider reported above inferred`() {
        assertEquals(3, songCreationConfidence(song(createdAtConfidence = "exact")))
        assertEquals(2, songCreationConfidence(song(createdAtConfidence = "Provider_Reported")))
        assertEquals(1, songCreationConfidence(song(createdAtConfidence = "INFERRED")))
        assertEquals(0, songCreationConfidence(song(createdAtConfidence = "UNKNOWN")))
        assertEquals(0, songCreationConfidence(song(createdAtConfidence = null)))
    }

    @Test
    fun `filesystem creation observation reflects the file attributes`() {
        val file = temporaryFolder.newFile("song.flac")
        val attributes = Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
        val creationTimeMs = attributes.creationTime().toMillis()

        val observation = resolveFilesystemCreationObservation(file)

        assertEquals(
            FilesystemCreationObservation(
                timestampMs = creationTimeMs,
                confidence = resolveFilesystemCreationConfidence(
                    creationTimeMs = creationTimeMs,
                    lastModifiedTimeMs = attributes.lastModifiedTime().toMillis()
                )
            ),
            observation
        )
        assertEquals(creationTimeMs, resolveFilesystemCreationTime(file))
    }

    @Test
    fun `missing files have no creation observation`() {
        val missing = File(temporaryFolder.root, "missing.flac")

        assertNull(resolveFilesystemCreationObservation(missing))
        assertNull(resolveFilesystemCreationTime(missing))
    }

    @Test
    fun `creation equal to modification time is only inferred`() {
        assertEquals("INFERRED", resolveFilesystemCreationConfidence(5_000L, 5_000L))
        assertEquals("EXACT", resolveFilesystemCreationConfidence(4_000L, 5_000L))
    }

    private fun song(
        logicalCreatedAtMs: Long? = null,
        createdAtSource: String? = null,
        createdAtConfidence: String? = null,
        addedAt: Long = 0L
    ) = SongItem(
        id = 1L,
        name = "Title",
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        addedAt = addedAt,
        logicalCreatedAtMs = logicalCreatedAtMs,
        createdAtSource = createdAtSource,
        createdAtConfidence = createdAtConfidence
    )
}
