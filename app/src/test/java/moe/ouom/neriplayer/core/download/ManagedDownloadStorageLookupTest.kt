package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadStorageLookupTest {

    @Test
    fun `audio lookup accepts numbered duplicate suffix`() {
        val expected = storedEntry(
            name = "Artist - Song (1).flac",
            reference = "/music/Artist - Song (1).flac",
            mediaUri = "/music/Artist - Song (1).flac"
        )
        val entries = listOf(
            storedEntry(
                name = "Artist - Other.flac",
                reference = "/music/Artist - Other.flac",
                mediaUri = "/music/Artist - Other.flac"
            ),
            expected
        )

        assertEquals(
            expected,
            ManagedDownloadStorageLookup.findAudioEntry(
                audioEntries = entries,
                baseNames = listOf("Artist - Song")
            )
        )
    }

    private fun storedEntry(
        name: String,
        reference: String,
        mediaUri: String
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = mediaUri,
            localFilePath = reference,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }
}
