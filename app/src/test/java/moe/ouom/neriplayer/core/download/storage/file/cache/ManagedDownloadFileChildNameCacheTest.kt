package moe.ouom.neriplayer.core.download.storage.file.cache

import java.io.File
import java.nio.file.Files
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadFileChildNameCacheTest {
    @Test
    fun `pending logical name remains reserved after file cache refresh`() {
        val directory = Files.createTempDirectory("neriplayer-file-name-cache").toFile()
        try {
            val desiredName = "Artist - Song.mp3"
            val pendingName = ManagedDownloadPendingAudioWriteNames()
                .buildPendingAudioWriteName(desiredName)
            File(directory, pendingName).writeText("audio")

            val cache = ManagedDownloadFileChildNameCache(
                writeCacheValidateIntervalMs = 60_000L
            )

            assertEquals(
                "Artist - Song (1).mp3",
                cache.reserveUniqueName(directory, desiredName)
            )
            assertEquals(
                "Artist - Song (2).mp3",
                cache.reserveUniqueName(directory, desiredName)
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
