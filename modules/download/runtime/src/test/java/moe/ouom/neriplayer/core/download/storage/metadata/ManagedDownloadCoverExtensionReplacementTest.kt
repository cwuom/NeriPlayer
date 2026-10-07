package moe.ouom.neriplayer.core.download.storage.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadCoverExtensionReplacementTest {
    @Test
    fun `cover extensions are replaced or appended with a normalized suffix`() {
        listOf(
            Triple("Song.jpg", "png", "Song.png"),
            Triple("Album.Live.png", "jpg", "Album.Live.jpg"),
            Triple("Song", " .webp ", "Song.webp"),
            Triple(".hidden", "jpg", ".hidden.jpg"),
            Triple("Song.jpg", "  ", "Song.bin")
        ).forEach { (fileName, extension, expected) ->
            assertEquals(
                "$fileName + $extension",
                expected,
                ManagedDownloadCoverAssetStore.replaceCoverFileExtension(fileName, extension)
            )
        }
    }
}
